uniform sampler2D color;
uniform sampler2D depth;
uniform sampler2D handDepth;
// set when the hand has its own depth, otherwise depth already holds the whole frame
uniform bool separateHand;
uniform vec2 renderSize;
// camera position minus last frame's, positions here are relative to the camera
uniform vec3 cameraDelta;

flat in mat4 inverseJitteredViewProjection;
flat in mat4 viewProjection;
flat in mat4 previousViewProjection;

layout(location = 0) out vec4 outColor;
layout(location = 1) out vec4 outDepth;
layout(location = 2) out vec4 outMotion;

void main() {
	ivec2 pixel = ivec2(gl_FragCoord.xy);
	float z = texelFetch(depth, pixel, 0).r;
	float hand = separateHand ? texelFetch(handDepth, pixel, 0).r : 1.0;
	outColor = vec4(texelFetch(color, pixel, 0).rgb, 1.0);
	outDepth = vec4(hand < 1.0 ? hand : z);

	// the hand follows the camera, so it stays still on screen
	if (hand < 1.0) {
		outMotion = vec4(0.0);
		return;
	}

	vec4 position = inverseJitteredViewProjection * vec4(vec3(gl_FragCoord.xy / renderSize, z) * 2.0 - 1.0, 1.0);
	vec4 current;
	vec4 previous;

	if (z < 1.0) {
		position /= position.w;
		current = viewProjection * position;
		previous = previousViewProjection * vec4(position.xyz + cameraDelta, 1.0);
	} else {
		// the sky is infinitely far away, so only rotation moves it
		position.w = 0.0;
		current = viewProjection * position;
		previous = previousViewProjection * position;
	}

	// pixels from here to where this surface was last frame, dlss reads them with low resolution motion vectors
	outMotion = vec4((previous.xy / previous.w - current.xy / current.w) * 0.5 * renderSize, 0.0, 0.0);
}
