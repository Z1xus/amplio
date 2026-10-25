#version 410

uniform mat4 projection;
uniform mat4 jitteredProjection;
uniform mat4 modelView;
uniform mat4 previousProjection;
uniform mat4 previousModelView;

flat out mat4 inverseJitteredViewProjection;
flat out mat4 viewProjection;
flat out mat4 previousViewProjection;

void main() {
	vec2 position = vec2((gl_VertexID << 1) & 2, gl_VertexID & 2);
	gl_Position = vec4(position * 2.0 - 1.0, 0.0, 1.0);
	inverseJitteredViewProjection = inverse(jitteredProjection * modelView);
	viewProjection = projection * modelView;
	previousViewProjection = previousProjection * previousModelView;
}
