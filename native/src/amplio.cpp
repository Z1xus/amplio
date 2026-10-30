// vulkan side of amplio: images and semaphores shared with the game's opengl context, and dlss on top of them
#include <vulkan/vulkan.h>

#include <nvsdk_ngx_helpers.h>
#include <nvsdk_ngx_helpers_vk.h>

#include <array>
#include <cstdint>
#include <cstring>
#include <string>
#include <type_traits>
#include <vector>

#ifdef _WIN32
#define AMPLIO_API extern "C" __declspec(dllexport)
#define NOMINMAX
#include <windows.h>
#include <vulkan/vulkan_win32.h>
constexpr VkExternalMemoryHandleTypeFlagBits MEMORY_HANDLE = VK_EXTERNAL_MEMORY_HANDLE_TYPE_OPAQUE_WIN32_BIT;
constexpr VkExternalSemaphoreHandleTypeFlagBits SEMAPHORE_HANDLE = VK_EXTERNAL_SEMAPHORE_HANDLE_TYPE_OPAQUE_WIN32_BIT;
constexpr const char *MEMORY_EXTENSION = VK_KHR_EXTERNAL_MEMORY_WIN32_EXTENSION_NAME;
constexpr const char *SEMAPHORE_EXTENSION = VK_KHR_EXTERNAL_SEMAPHORE_WIN32_EXTENSION_NAME;
#else
#define AMPLIO_API extern "C" __attribute__((visibility("default")))
#include <pthread.h>
constexpr VkExternalMemoryHandleTypeFlagBits MEMORY_HANDLE = VK_EXTERNAL_MEMORY_HANDLE_TYPE_OPAQUE_FD_BIT;
constexpr VkExternalSemaphoreHandleTypeFlagBits SEMAPHORE_HANDLE = VK_EXTERNAL_SEMAPHORE_HANDLE_TYPE_OPAQUE_FD_BIT;
constexpr const char *MEMORY_EXTENSION = VK_KHR_EXTERNAL_MEMORY_FD_EXTENSION_NAME;
constexpr const char *SEMAPHORE_EXTENSION = VK_KHR_EXTERNAL_SEMAPHORE_FD_EXTENSION_NAME;
#endif

namespace {
// identifies amplio to the driver, any guid shaped string works for custom engines
constexpr const char *PROJECT_ID = "6f2a9d3e-4b1c-4e8a-9f07-a3c5d1e2b4f6";
// matches the frames the opengl driver queues, so the render thread never waits on a fence while the gpu is the limit
constexpr int FRAMES = 3;

enum Image { COLOR, DEPTH, MOTION, OUTPUT, IMAGE_COUNT };
constexpr std::array<VkFormat, IMAGE_COUNT> FORMATS = {VK_FORMAT_R8G8B8A8_UNORM, VK_FORMAT_R32_SFLOAT, VK_FORMAT_R16G16_SFLOAT, VK_FORMAT_R8G8B8A8_UNORM};

// layouts shared with java, every field is 8 bytes wide so both sides agree without padding rules
struct CreateInfo {
    uint8_t deviceUuid[16];
    const char *dataPath;
};

struct ResizeInfo {
    int64_t renderWidth, renderHeight, outputWidth, outputHeight, quality, preset;
    int64_t memoryHandles[IMAGE_COUNT];
    int64_t memorySizes[IMAGE_COUNT];
    int64_t glReady, vkReady;
};

struct QueryInfo {
    int64_t outputWidth, outputHeight, quality;
    int64_t renderWidth, renderHeight;
};

struct EvaluateInfo {
    float jitterX, jitterY, frameTime, reset;
};

struct SharedImage {
    VkImage image = VK_NULL_HANDLE;
    VkDeviceMemory memory = VK_NULL_HANDLE;
    VkImageView view = VK_NULL_HANDLE;
    NVSDK_NGX_Resource_VK resource{};
};

struct Context {
    std::string error;
    VkInstance instance = VK_NULL_HANDLE;
    VkPhysicalDevice physicalDevice = VK_NULL_HANDLE;
    VkDevice device = VK_NULL_HANDLE;
    VkQueue queue = VK_NULL_HANDLE;
    uint32_t queueFamily = 0;
    VkCommandPool pool = VK_NULL_HANDLE;
    std::array<VkCommandBuffer, FRAMES> commands{};
    std::array<VkFence, FRAMES> fences{};
    uint32_t frame = 0;
    bool ngx = false;
    NVSDK_NGX_Parameter *parameters = nullptr;
    NVSDK_NGX_Handle *dlss = nullptr;
    std::array<SharedImage, IMAGE_COUNT> images{};
    VkSemaphore glReady = VK_NULL_HANDLE;
    VkSemaphore vkReady = VK_NULL_HANDLE;
    uint32_t renderWidth = 0, renderHeight = 0;
    std::wstring dataPath;
    std::vector<int64_t> exported;
#ifdef _WIN32
    PFN_vkGetMemoryWin32HandleKHR getMemoryHandle = nullptr;
    PFN_vkGetSemaphoreWin32HandleKHR getSemaphoreHandle = nullptr;
#else
    PFN_vkGetMemoryFdKHR getMemoryHandle = nullptr;
    PFN_vkGetSemaphoreFdKHR getSemaphoreHandle = nullptr;
#endif
};

// ngx init and shutdown need more stack than the jvm's 1 mb main thread has, so they run on their own thread
template<class F>
void runOnLargeStack(F &&function) {
    constexpr size_t STACK = 16 << 20;
#ifdef _WIN32
    HANDLE thread = CreateThread(nullptr, STACK, [](LPVOID f) -> DWORD { (*static_cast<std::remove_reference_t<F> *>(f))(); return 0; }, &function, STACK_SIZE_PARAM_IS_A_RESERVATION, nullptr);
    WaitForSingleObject(thread, INFINITE);
    CloseHandle(thread);
#else
    pthread_attr_t attributes;
    pthread_attr_init(&attributes);
    pthread_attr_setstacksize(&attributes, STACK);
    pthread_t thread;
    pthread_create(&thread, &attributes, [](void *f) -> void * { (*static_cast<std::remove_reference_t<F> *>(f))(); return nullptr; }, &function);
    pthread_join(thread, nullptr);
    pthread_attr_destroy(&attributes);
#endif
}

int fail(Context *context, const std::string &message) {
    context->error = message;
    return 0;
}

int check(Context *context, VkResult result, const char *what) {
    return result == VK_SUCCESS ? 1 : fail(context, std::string(what) + " failed with " + std::to_string(result));
}

std::wstring widen(const char *utf8) {
#ifdef _WIN32
    int length = MultiByteToWideChar(CP_UTF8, 0, utf8, -1, nullptr, 0);
    std::wstring out(length > 0 ? length - 1 : 0, L'\0');
    MultiByteToWideChar(CP_UTF8, 0, utf8, -1, out.data(), length);
    return out;
#else
    std::wstring out;
    for (const auto *p = reinterpret_cast<const unsigned char *>(utf8); *p;) {
        uint32_t c = *p++;
        int extra = c >= 0xF0 ? 3 : c >= 0xE0 ? 2 : c >= 0xC0 ? 1 : 0;
        c &= extra == 3 ? 0x07 : extra == 2 ? 0x0F : extra == 1 ? 0x1F : 0x7F;
        for (; extra > 0 && *p; extra--) {
            c = (c << 6) | (*p++ & 0x3F);
        }
        out.push_back(static_cast<wchar_t>(c));
    }
    return out;
#endif
}

bool hasExtension(const std::vector<VkExtensionProperties> &available, const char *name) {
    for (const auto &extension : available) {
        if (std::strcmp(extension.extensionName, name) == 0) {
            return true;
        }
    }
    return false;
}

int createInstance(Context *context) {
    unsigned int instanceCount = 0, deviceCount = 0;
    const char **instanceExtensions = nullptr, **deviceExtensions = nullptr;
    NVSDK_NGX_VULKAN_RequiredExtensions(&instanceCount, &instanceExtensions, &deviceCount, &deviceExtensions);

    uint32_t count = 0;
    vkEnumerateInstanceExtensionProperties(nullptr, &count, nullptr);
    std::vector<VkExtensionProperties> available(count);
    vkEnumerateInstanceExtensionProperties(nullptr, &count, available.data());

    std::vector<const char *> enabled;
    for (unsigned int i = 0; i < instanceCount; i++) {
        if (hasExtension(available, instanceExtensions[i])) {
            enabled.push_back(instanceExtensions[i]);
        }
    }

    VkApplicationInfo app{VK_STRUCTURE_TYPE_APPLICATION_INFO};
    app.pApplicationName = "Amplio";
    app.pEngineName = "Minecraft";
    app.apiVersion = VK_API_VERSION_1_2;

    VkInstanceCreateInfo info{VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO};
    info.pApplicationInfo = &app;
    info.enabledExtensionCount = static_cast<uint32_t>(enabled.size());
    info.ppEnabledExtensionNames = enabled.data();
    return check(context, vkCreateInstance(&info, nullptr, &context->instance), "vkCreateInstance");
}

// the vulkan device must be the gpu that runs the game's opengl context, matched by uuid
int pickDevice(Context *context, const uint8_t *uuid) {
    uint32_t count = 0;
    vkEnumeratePhysicalDevices(context->instance, &count, nullptr);
    std::vector<VkPhysicalDevice> devices(count);
    vkEnumeratePhysicalDevices(context->instance, &count, devices.data());

    for (VkPhysicalDevice device : devices) {
        VkPhysicalDeviceIDProperties id{VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_ID_PROPERTIES};
        VkPhysicalDeviceProperties2 properties{VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_PROPERTIES_2};
        properties.pNext = &id;
        vkGetPhysicalDeviceProperties2(device, &properties);

        if (std::memcmp(id.deviceUUID, uuid, VK_UUID_SIZE) == 0) {
            context->physicalDevice = device;
            return 1;
        }
    }

    return fail(context, "No Vulkan device matches the OpenGL device");
}

int createDevice(Context *context) {
    uint32_t familyCount = 0;
    vkGetPhysicalDeviceQueueFamilyProperties(context->physicalDevice, &familyCount, nullptr);
    std::vector<VkQueueFamilyProperties> families(familyCount);
    vkGetPhysicalDeviceQueueFamilyProperties(context->physicalDevice, &familyCount, families.data());

    bool found = false;
    for (uint32_t i = 0; i < familyCount && !found; i++) {
        if ((families[i].queueFlags & (VK_QUEUE_GRAPHICS_BIT | VK_QUEUE_COMPUTE_BIT)) == (VK_QUEUE_GRAPHICS_BIT | VK_QUEUE_COMPUTE_BIT)) {
            context->queueFamily = i;
            found = true;
        }
    }

    if (!found) {
        return fail(context, "No graphics queue");
    }

    unsigned int instanceCount = 0, deviceCount = 0;
    const char **instanceExtensions = nullptr, **deviceExtensions = nullptr;
    NVSDK_NGX_VULKAN_RequiredExtensions(&instanceCount, &instanceExtensions, &deviceCount, &deviceExtensions);

    uint32_t count = 0;
    vkEnumerateDeviceExtensionProperties(context->physicalDevice, nullptr, &count, nullptr);
    std::vector<VkExtensionProperties> available(count);
    vkEnumerateDeviceExtensionProperties(context->physicalDevice, nullptr, &count, available.data());

    std::vector<const char *> enabled = {MEMORY_EXTENSION, SEMAPHORE_EXTENSION};
    for (const char *name : enabled) {
        if (!hasExtension(available, name)) {
            return fail(context, std::string("Missing device extension ") + name);
        }
    }

    for (unsigned int i = 0; i < deviceCount; i++) {
        if (hasExtension(available, deviceExtensions[i])) {
            enabled.push_back(deviceExtensions[i]);
        }
    }

    float priority = 1.0F;
    VkDeviceQueueCreateInfo queue{VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO};
    queue.queueFamilyIndex = context->queueFamily;
    queue.queueCount = 1;
    queue.pQueuePriorities = &priority;

    VkPhysicalDeviceVulkan12Features features12{VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_2_FEATURES};
    features12.bufferDeviceAddress = VK_TRUE;

    VkDeviceCreateInfo info{VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO};
    info.pNext = &features12;
    info.queueCreateInfoCount = 1;
    info.pQueueCreateInfos = &queue;
    info.enabledExtensionCount = static_cast<uint32_t>(enabled.size());
    info.ppEnabledExtensionNames = enabled.data();

    if (!check(context, vkCreateDevice(context->physicalDevice, &info, nullptr, &context->device), "vkCreateDevice")) {
        return 0;
    }

    vkGetDeviceQueue(context->device, context->queueFamily, 0, &context->queue);
#ifdef _WIN32
    context->getMemoryHandle = reinterpret_cast<PFN_vkGetMemoryWin32HandleKHR>(vkGetDeviceProcAddr(context->device, "vkGetMemoryWin32HandleKHR"));
    context->getSemaphoreHandle = reinterpret_cast<PFN_vkGetSemaphoreWin32HandleKHR>(vkGetDeviceProcAddr(context->device, "vkGetSemaphoreWin32HandleKHR"));
#else
    context->getMemoryHandle = reinterpret_cast<PFN_vkGetMemoryFdKHR>(vkGetDeviceProcAddr(context->device, "vkGetMemoryFdKHR"));
    context->getSemaphoreHandle = reinterpret_cast<PFN_vkGetSemaphoreFdKHR>(vkGetDeviceProcAddr(context->device, "vkGetSemaphoreFdKHR"));
#endif

    VkCommandPoolCreateInfo poolInfo{VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO};
    poolInfo.flags = VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT;
    poolInfo.queueFamilyIndex = context->queueFamily;
    if (!check(context, vkCreateCommandPool(context->device, &poolInfo, nullptr, &context->pool), "vkCreateCommandPool")) {
        return 0;
    }

    VkCommandBufferAllocateInfo allocate{VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO};
    allocate.commandPool = context->pool;
    allocate.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY;
    allocate.commandBufferCount = FRAMES;
    if (!check(context, vkAllocateCommandBuffers(context->device, &allocate, context->commands.data()), "vkAllocateCommandBuffers")) {
        return 0;
    }

    VkFenceCreateInfo fenceInfo{VK_STRUCTURE_TYPE_FENCE_CREATE_INFO};
    fenceInfo.flags = VK_FENCE_CREATE_SIGNALED_BIT;
    for (VkFence &fence : context->fences) {
        if (!check(context, vkCreateFence(context->device, &fenceInfo, nullptr, &fence), "vkCreateFence")) {
            return 0;
        }
    }

    return 1;
}

int initNgx(Context *context) {
    const wchar_t *paths[] = {context->dataPath.c_str()};
    NVSDK_NGX_FeatureCommonInfo common{};
    common.PathListInfo.Path = paths;
    common.PathListInfo.Length = 1;
    common.LoggingInfo.MinimumLoggingLevel = NVSDK_NGX_LOGGING_LEVEL_OFF;

    NVSDK_NGX_Result result = NVSDK_NGX_VULKAN_Init_with_ProjectID(PROJECT_ID, NVSDK_NGX_ENGINE_TYPE_CUSTOM, "1.8.9", context->dataPath.c_str(),
        context->instance, context->physicalDevice, context->device, vkGetInstanceProcAddr, vkGetDeviceProcAddr, &common);
    if (NVSDK_NGX_FAILED(result)) {
        return fail(context, "NGX init failed with " + std::to_string(result));
    }

    context->ngx = true;
    if (NVSDK_NGX_FAILED(NVSDK_NGX_VULKAN_GetCapabilityParameters(&context->parameters))) {
        return fail(context, "NGX capability query failed");
    }

    int available = 0;
    NVSDK_NGX_Parameter_GetI(context->parameters, NVSDK_NGX_Parameter_SuperSampling_Available, &available);
    return available ? 1 : fail(context, "DLSS is not available on this GPU or driver");
}

int64_t exportMemory(Context *context, VkDeviceMemory memory) {
#ifdef _WIN32
    VkMemoryGetWin32HandleInfoKHR info{VK_STRUCTURE_TYPE_MEMORY_GET_WIN32_HANDLE_INFO_KHR};
    info.memory = memory;
    info.handleType = MEMORY_HANDLE;
    HANDLE handle = nullptr;
    return context->getMemoryHandle(context->device, &info, &handle) == VK_SUCCESS ? reinterpret_cast<int64_t>(handle) : -1;
#else
    VkMemoryGetFdInfoKHR info{VK_STRUCTURE_TYPE_MEMORY_GET_FD_INFO_KHR};
    info.memory = memory;
    info.handleType = MEMORY_HANDLE;
    int fd = -1;
    return context->getMemoryHandle(context->device, &info, &fd) == VK_SUCCESS ? fd : -1;
#endif
}

int64_t exportSemaphore(Context *context, VkSemaphore semaphore) {
#ifdef _WIN32
    VkSemaphoreGetWin32HandleInfoKHR info{VK_STRUCTURE_TYPE_SEMAPHORE_GET_WIN32_HANDLE_INFO_KHR};
    info.semaphore = semaphore;
    info.handleType = SEMAPHORE_HANDLE;
    HANDLE handle = nullptr;
    return context->getSemaphoreHandle(context->device, &info, &handle) == VK_SUCCESS ? reinterpret_cast<int64_t>(handle) : -1;
#else
    VkSemaphoreGetFdInfoKHR info{VK_STRUCTURE_TYPE_SEMAPHORE_GET_FD_INFO_KHR};
    info.semaphore = semaphore;
    info.handleType = SEMAPHORE_HANDLE;
    int fd = -1;
    return context->getSemaphoreHandle(context->device, &info, &fd) == VK_SUCCESS ? fd : -1;
#endif
}

uint32_t findMemoryType(Context *context, uint32_t bits) {
    VkPhysicalDeviceMemoryProperties properties;
    vkGetPhysicalDeviceMemoryProperties(context->physicalDevice, &properties);
    for (uint32_t i = 0; i < properties.memoryTypeCount; i++) {
        if ((bits & (1U << i)) && (properties.memoryTypes[i].propertyFlags & VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT)) {
            return i;
        }
    }
    return UINT32_MAX;
}

int createImage(Context *context, SharedImage &shared, VkFormat format, uint32_t width, uint32_t height, bool output, int64_t &handle, int64_t &size) {
    VkExternalMemoryImageCreateInfo external{VK_STRUCTURE_TYPE_EXTERNAL_MEMORY_IMAGE_CREATE_INFO};
    external.handleTypes = MEMORY_HANDLE;

    VkImageCreateInfo info{VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO};
    info.pNext = &external;
    info.imageType = VK_IMAGE_TYPE_2D;
    info.format = format;
    info.extent = {width, height, 1};
    info.mipLevels = 1;
    info.arrayLayers = 1;
    info.samples = VK_SAMPLE_COUNT_1_BIT;
    info.tiling = VK_IMAGE_TILING_OPTIMAL;
    // opengl renders into the inputs and samples the output, dlss reads the inputs and writes the output
    info.usage = VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT;
    info.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
    info.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;
    if (!check(context, vkCreateImage(context->device, &info, nullptr, &shared.image), "vkCreateImage")) {
        return 0;
    }

    VkMemoryRequirements requirements;
    vkGetImageMemoryRequirements(context->device, shared.image, &requirements);

    // opengl imports these as dedicated memory objects, so each image owns its allocation
    VkMemoryDedicatedAllocateInfo dedicated{VK_STRUCTURE_TYPE_MEMORY_DEDICATED_ALLOCATE_INFO};
    dedicated.image = shared.image;
    VkExportMemoryAllocateInfo exportInfo{VK_STRUCTURE_TYPE_EXPORT_MEMORY_ALLOCATE_INFO};
    exportInfo.pNext = &dedicated;
    exportInfo.handleTypes = MEMORY_HANDLE;

    VkMemoryAllocateInfo allocate{VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO};
    allocate.pNext = &exportInfo;
    allocate.allocationSize = requirements.size;
    allocate.memoryTypeIndex = findMemoryType(context, requirements.memoryTypeBits);
    if (allocate.memoryTypeIndex == UINT32_MAX) {
        return fail(context, "No device local memory type");
    }

    if (!check(context, vkAllocateMemory(context->device, &allocate, nullptr, &shared.memory), "vkAllocateMemory")
        || !check(context, vkBindImageMemory(context->device, shared.image, shared.memory, 0), "vkBindImageMemory")) {
        return 0;
    }

    VkImageViewCreateInfo view{VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO};
    view.image = shared.image;
    view.viewType = VK_IMAGE_VIEW_TYPE_2D;
    view.format = format;
    view.subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
    if (!check(context, vkCreateImageView(context->device, &view, nullptr, &shared.view), "vkCreateImageView")) {
        return 0;
    }

    shared.resource = NVSDK_NGX_Create_ImageView_Resource_VK(shared.view, shared.image, view.subresourceRange, format, width, height, output);
    handle = exportMemory(context, shared.memory);
    context->exported.push_back(handle);
    size = static_cast<int64_t>(requirements.size);
    return handle == -1 ? fail(context, "Failed to export image memory") : 1;
}

int createSemaphore(Context *context, VkSemaphore &semaphore, int64_t &handle) {
    VkExportSemaphoreCreateInfo exportInfo{VK_STRUCTURE_TYPE_EXPORT_SEMAPHORE_CREATE_INFO};
    exportInfo.handleTypes = SEMAPHORE_HANDLE;
    VkSemaphoreCreateInfo info{VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO};
    info.pNext = &exportInfo;
    if (!check(context, vkCreateSemaphore(context->device, &info, nullptr, &semaphore), "vkCreateSemaphore")) {
        return 0;
    }

    handle = exportSemaphore(context, semaphore);
    context->exported.push_back(handle);
    return handle == -1 ? fail(context, "Failed to export semaphore") : 1;
}

void destroyTargets(Context *context) {
    if (context->dlss != nullptr) {
        NVSDK_NGX_VULKAN_ReleaseFeature(context->dlss);
        context->dlss = nullptr;
    }

    for (SharedImage &shared : context->images) {
        vkDestroyImageView(context->device, shared.view, nullptr);
        vkDestroyImage(context->device, shared.image, nullptr);
        vkFreeMemory(context->device, shared.memory, nullptr);
        shared = {};
    }

    vkDestroySemaphore(context->device, context->glReady, nullptr);
    vkDestroySemaphore(context->device, context->vkReady, nullptr);
    context->glReady = VK_NULL_HANDLE;
    context->vkReady = VK_NULL_HANDLE;
}

int submitAndWait(Context *context, VkCommandBuffer command) {
    VkSubmitInfo submit{VK_STRUCTURE_TYPE_SUBMIT_INFO};
    submit.commandBufferCount = 1;
    submit.pCommandBuffers = &command;
    return check(context, vkQueueSubmit(context->queue, 1, &submit, VK_NULL_HANDLE), "vkQueueSubmit")
        && check(context, vkQueueWaitIdle(context->queue), "vkQueueWaitIdle");
}

// moves every shared image between opengl (the external queue family) and our queue
void transfer(Context *context, VkCommandBuffer command, bool acquire) {
    std::array<VkImageMemoryBarrier, IMAGE_COUNT> barriers{};
    for (int i = 0; i < IMAGE_COUNT; i++) {
        VkImageMemoryBarrier &barrier = barriers[i];
        barrier.sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
        barrier.srcAccessMask = acquire ? 0 : VK_ACCESS_SHADER_WRITE_BIT;
        barrier.dstAccessMask = acquire ? VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT : 0;
        barrier.oldLayout = VK_IMAGE_LAYOUT_GENERAL;
        barrier.newLayout = VK_IMAGE_LAYOUT_GENERAL;
        barrier.srcQueueFamilyIndex = acquire ? VK_QUEUE_FAMILY_EXTERNAL : context->queueFamily;
        barrier.dstQueueFamilyIndex = acquire ? context->queueFamily : VK_QUEUE_FAMILY_EXTERNAL;
        barrier.image = context->images[i].image;
        barrier.subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
    }

    vkCmdPipelineBarrier(command, acquire ? VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT : VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,
        acquire ? VK_PIPELINE_STAGE_ALL_COMMANDS_BIT : VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT, 0, 0, nullptr, 0, nullptr, IMAGE_COUNT, barriers.data());
}

void destroy(Context *context) {
    if (context->device != VK_NULL_HANDLE) {
        vkDeviceWaitIdle(context->device);
        destroyTargets(context);

        if (context->parameters != nullptr) {
            NVSDK_NGX_VULKAN_DestroyParameters(context->parameters);
        }

        if (context->ngx) {
            NVSDK_NGX_VULKAN_Shutdown1(context->device);
        }

        for (VkFence fence : context->fences) {
            vkDestroyFence(context->device, fence, nullptr);
        }

        vkDestroyCommandPool(context->device, context->pool, nullptr);
        vkDestroyDevice(context->device, nullptr);
    }

    if (context->instance != VK_NULL_HANDLE) {
        vkDestroyInstance(context->instance, nullptr);
    }
}
}

AMPLIO_API Context *amplio_create(const CreateInfo *info) {
    auto *context = new Context();
    context->dataPath = widen(info->dataPath);

    runOnLargeStack([&] {
        if (createInstance(context) && pickDevice(context, info->deviceUuid) && createDevice(context)) {
            initNgx(context);
        }
    });

    return context;
}

AMPLIO_API const char *amplio_error(Context *context) {
    return context->error.c_str();
}

AMPLIO_API int amplio_query(Context *context, QueryInfo *info) {
    unsigned int width = 0, height = 0, maxWidth = 0, maxHeight = 0, minWidth = 0, minHeight = 0;
    float sharpness = 0.0F;
    NVSDK_NGX_Result result = NGX_DLSS_GET_OPTIMAL_SETTINGS(context->parameters, static_cast<unsigned int>(info->outputWidth),
        static_cast<unsigned int>(info->outputHeight), static_cast<NVSDK_NGX_PerfQuality_Value>(info->quality),
        &width, &height, &maxWidth, &maxHeight, &minWidth, &minHeight, &sharpness);
    if (NVSDK_NGX_FAILED(result) || width == 0 || height == 0) {
        return fail(context, "DLSS has no render size for this quality");
    }

    info->renderWidth = width;
    info->renderHeight = height;
    return 1;
}

AMPLIO_API int amplio_resize(Context *context, ResizeInfo *info) {
    vkDeviceWaitIdle(context->device);
    destroyTargets(context);

    context->renderWidth = static_cast<uint32_t>(info->renderWidth);
    context->renderHeight = static_cast<uint32_t>(info->renderHeight);
    auto outputWidth = static_cast<uint32_t>(info->outputWidth);
    auto outputHeight = static_cast<uint32_t>(info->outputHeight);

    for (int i = 0; i < IMAGE_COUNT; i++) {
        bool output = i == OUTPUT;
        if (!createImage(context, context->images[i], FORMATS[i], output ? outputWidth : context->renderWidth, output ? outputHeight : context->renderHeight,
                output, info->memoryHandles[i], info->memorySizes[i])) {
            return 0;
        }
    }

    if (!createSemaphore(context, context->glReady, info->glReady) || !createSemaphore(context, context->vkReady, info->vkReady)) {
        return 0;
    }

    NVSDK_NGX_DLSS_Create_Params create{};
    create.Feature.InWidth = context->renderWidth;
    create.Feature.InHeight = context->renderHeight;
    create.Feature.InTargetWidth = outputWidth;
    create.Feature.InTargetHeight = outputHeight;
    create.Feature.InPerfQualityValue = static_cast<NVSDK_NGX_PerfQuality_Value>(info->quality);
    create.InFeatureCreateFlags = NVSDK_NGX_DLSS_Feature_Flags_MVLowRes | NVSDK_NGX_DLSS_Feature_Flags_AutoExposure;

    for (const char *key : {NVSDK_NGX_Parameter_DLSS_Hint_Render_Preset_DLAA, NVSDK_NGX_Parameter_DLSS_Hint_Render_Preset_Quality, NVSDK_NGX_Parameter_DLSS_Hint_Render_Preset_Balanced,
             NVSDK_NGX_Parameter_DLSS_Hint_Render_Preset_Performance, NVSDK_NGX_Parameter_DLSS_Hint_Render_Preset_UltraPerformance}) {
        NVSDK_NGX_Parameter_SetUI(context->parameters, key, static_cast<unsigned int>(info->preset));
    }

    VkCommandBuffer command = context->commands[0];
    VkCommandBufferBeginInfo begin{VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO};
    begin.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
    vkResetCommandBuffer(command, 0);
    vkBeginCommandBuffer(command, &begin);
    NVSDK_NGX_Result result = NGX_VULKAN_CREATE_DLSS_EXT1(context->device, command, 1, 1, &context->dlss, context->parameters, &create);
    vkEndCommandBuffer(command);

    if (NVSDK_NGX_FAILED(result)) {
        return fail(context, "DLSS feature creation failed with " + std::to_string(result));
    }

    return submitAndWait(context, command);
}

// opengl takes ownership of imported fds, but win32 handles stay open until closed
AMPLIO_API void amplio_close_handles(Context *context) {
#ifdef _WIN32
    for (int64_t handle : context->exported) {
        if (handle != -1) {
            CloseHandle(reinterpret_cast<HANDLE>(handle));
        }
    }
#endif
    context->exported.clear();
}

AMPLIO_API int amplio_evaluate(Context *context, const EvaluateInfo *info) {
    uint32_t index = context->frame++ % FRAMES;
    VkCommandBuffer command = context->commands[index];
    vkWaitForFences(context->device, 1, &context->fences[index], VK_TRUE, UINT64_MAX);
    vkResetFences(context->device, 1, &context->fences[index]);

    VkCommandBufferBeginInfo begin{VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO};
    begin.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
    vkResetCommandBuffer(command, 0);
    vkBeginCommandBuffer(command, &begin);
    transfer(context, command, true);

    NVSDK_NGX_VK_DLSS_Eval_Params eval{};
    eval.Feature.pInColor = &context->images[COLOR].resource;
    eval.Feature.pInOutput = &context->images[OUTPUT].resource;
    eval.pInDepth = &context->images[DEPTH].resource;
    eval.pInMotionVectors = &context->images[MOTION].resource;
    eval.InJitterOffsetX = info->jitterX;
    eval.InJitterOffsetY = info->jitterY;
    eval.InRenderSubrectDimensions = {context->renderWidth, context->renderHeight};
    eval.InReset = info->reset != 0.0F;
    eval.InMVScaleX = 1.0F;
    eval.InMVScaleY = 1.0F;
    eval.InFrameTimeDeltaInMsec = info->frameTime;
    NVSDK_NGX_Result result = NGX_VULKAN_EVALUATE_DLSS_EXT(command, context->dlss, context->parameters, &eval);

    transfer(context, command, false);
    vkEndCommandBuffer(command);

    if (NVSDK_NGX_FAILED(result)) {
        // the semaphore pair still has to complete, so the empty command buffer is submitted anyway
        fail(context, "DLSS evaluation failed with " + std::to_string(result));
    }

    VkPipelineStageFlags stage = VK_PIPELINE_STAGE_ALL_COMMANDS_BIT;
    VkSubmitInfo submit{VK_STRUCTURE_TYPE_SUBMIT_INFO};
    submit.waitSemaphoreCount = 1;
    submit.pWaitSemaphores = &context->glReady;
    submit.pWaitDstStageMask = &stage;
    submit.commandBufferCount = 1;
    submit.pCommandBuffers = &command;
    submit.signalSemaphoreCount = 1;
    submit.pSignalSemaphores = &context->vkReady;
    return check(context, vkQueueSubmit(context->queue, 1, &submit, context->fences[index]), "vkQueueSubmit") && !NVSDK_NGX_FAILED(result);
}

AMPLIO_API void amplio_destroy(Context *context) {
    runOnLargeStack([&] { destroy(context); });
    delete context;
}
