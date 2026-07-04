#version 300 es

precision highp float;
precision highp int;

in vec2 position;
out vec2 screenCoord;

void main()
{
    screenCoord = ((position + 1.0) * 0.5);
    gl_Position = vec4(position, 0.0, 1.0);
} 