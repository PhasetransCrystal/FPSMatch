#version 150
uniform sampler2D Sampler0;
uniform vec4 ColorModulator;
in vec2 texCoord0;
in vec4 vertexColor;
out vec4 fragColor;
void main() {
    // Coverage includes very faint edge samples; an alpha cutoff would erase them.
    fragColor = texture(Sampler0, texCoord0) * vertexColor * ColorModulator;
}
