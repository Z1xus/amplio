uniform sampler2D source;
uniform float sharpness;

layout(location = 0) out vec4 fragColor;

AF4 FsrRcasLoadF(ASU2 p) { return texelFetch(source, p, 0); }
void FsrRcasInputF(inout AF1 r, inout AF1 g, inout AF1 b) {}

#define FSR_RCAS_F 1
#include "ffx_fsr1.h"

void main() {
	AU4 con = AU4(floatBitsToUint(exp2(-sharpness)), 0u, 0u, 0u);

	AF3 color;
	FsrRcasF(color.r, color.g, color.b, AU2(gl_FragCoord.xy), con);
	fragColor = vec4(color, 1.0);
}
