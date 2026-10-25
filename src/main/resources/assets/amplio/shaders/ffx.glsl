#define A_GPU 1
#define A_GLSL 1
// glsl 4.10 has no half packing, and ffx_a.h's helpers for it are unused by fsr 1
#define packHalf2x16 amplio_packHalf2x16
#define unpackHalf2x16 amplio_unpackHalf2x16
uint amplio_packHalf2x16(vec2 v) { return 0u; }
vec2 amplio_unpackHalf2x16(uint v) { return vec2(0.0); }
#include "ffx_a.h"
