#include "ffx.glsl"

uniform sampler2D source;
uniform vec2 inputSize;
uniform vec2 outputSize;

layout(location = 0) out vec4 fragColor;

AF4 FsrEasuRF(AF2 p) { return textureGather(source, p, 0); }
AF4 FsrEasuGF(AF2 p) { return textureGather(source, p, 1); }
AF4 FsrEasuBF(AF2 p) { return textureGather(source, p, 2); }

#define FSR_EASU_F 1
#include "ffx_fsr1.h"

void main() {
	AU4 con0, con1, con2, con3;
	FsrEasuCon(con0, con1, con2, con3, inputSize.x, inputSize.y, inputSize.x, inputSize.y, outputSize.x, outputSize.y);

	AF3 color;
	FsrEasuF(color, AU2(gl_FragCoord.xy), con0, con1, con2, con3);
	fragColor = vec4(color, 1.0);
}
