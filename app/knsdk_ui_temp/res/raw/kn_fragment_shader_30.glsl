#version 300 es

precision highp float;
precision highp int;

in vec4 DestinationColor;
in vec2 TexCoordOut;
in float LinePatternSeed;

out vec4 fragColor;

uniform sampler2D Texture;
uniform int FragmentType;
uniform int U_pattern;
uniform float Time;
uniform vec4 texRegion;
uniform vec4 TexChannelColor;

vec4 edgeGradientTexture(vec4 texture, vec2 texCoord)
{
    vec4 texColor = texture;
    float min = 0.1;
    float max = 0.25;
    float edge = smoothstep(min, max, texCoord.x) *
                 smoothstep(min, max, 1.0 - texCoord.x) *
                 smoothstep(min, max, texCoord.y) *
                 smoothstep(min, max, 1.0 - texCoord.y);
//    vec4 gradientColor = vec4(0.5, 0.5, 0.5, 0.5);

    vec4 mixColor_s = vec4(.05, .05, .05, 0.22);
    vec4 mixColor = vec4(0.05, 0.05, 0.05, 0.25);
    float edgeFactor = edge;
    vec4 mix = mix(mixColor_s, mixColor, edgeFactor);
    return texColor * mix;
}

vec4 edgeGradientTextureWithColor(vec4 texture, vec2 texCoord, vec4 mixColor_s, vec4 mixColor)
{
    vec4 texColor = texture;
    float min = 0.1;
    float max = 0.25;
    float edge = smoothstep(min, max, texCoord.x) *
    smoothstep(min, max, 1.0 - texCoord.x) *
    smoothstep(min, max, texCoord.y) *
    smoothstep(min, max, 1.0 - texCoord.y);
    //    vec4 gradientColor = vec4(0.5, 0.5, 0.5, 0.5);

//    vec4 mixColor_s = vec4(.05, .05, .05, 0.22);
//    vec4 mixColor = vec4(0.05, 0.05, 0.05, 0.25);
    float edgeFactor = edge;
    vec4 mix = mix(mixColor_s, mixColor, edgeFactor);
    return texColor * mix;
}

float linePattern(float frequency)
{
    return mod(LinePatternSeed * frequency, 1.0f);
}

void main(void)
{
    if (FragmentType == 1)
    {
        float regionWidth = (texRegion[2] - texRegion[0]);
        float regionHeight = (texRegion[1] - texRegion[3]);

        vec2 texCoord = vec2(
            texRegion[0] + (TexCoordOut.x * regionWidth),
            (1.0f - texRegion[1]) + (TexCoordOut.y * regionHeight));

        vec4 texColor = texture(Texture, texCoord);

        texColor.a *= Time * 0.004;//Time * 0.001;
        if (texColor.a<0.05)
        discard;
        fragColor = texColor;
    }
    else if (FragmentType == 2)
    {
        int patternType = U_pattern;

        if (patternType == 1)
        {
            float patternVal = linePattern(0.08f);
            if (patternVal > 0.62f)
                discard;
        }
        else if (patternType == 2)
        {
            float patternVal = linePattern(0.2f);
            if (patternVal > 0.5f)
                discard;
        }
        else if (patternType == 3)
        {
            float patternVal = linePattern(0.08f);

            if (patternVal < 0.1f)
                discard;
            else if ((patternVal > 0.3f) && (patternVal < 0.4f))
                discard;
        }
        else if (patternType == 4)
        {
            float patternVal = linePattern(0.08f);

            if (patternVal < 0.1f)
                discard;
            else if ((patternVal > 0.2f) && (patternVal < 0.3f))
                discard;
            else if ((patternVal > 0.4f) && (patternVal < 0.5f))
                discard;
        }

        fragColor = DestinationColor;
    }
    else if (FragmentType == 11)
    {
        int patternType = U_pattern;

        if (patternType == 1)
        {
            float patternVal = linePattern(0.08f);
            if (patternVal > 0.62f)
            discard;
        }
        else if (patternType == 2)
        {
            float patternVal = linePattern(0.2f);
            if (patternVal > 0.5f)
            discard;
        }
        else if (patternType == 3)
        {
            float patternVal = linePattern(0.08f);

            if (patternVal < 0.1f)
            discard;
            else if ((patternVal > 0.3f) && (patternVal < 0.4f))
            discard;
        }
        else if (patternType == 4)
        {
            float patternVal = linePattern(0.08f);

            if (patternVal < 0.1f)
            discard;
            else if ((patternVal > 0.2f) && (patternVal < 0.3f))
            discard;
            else if ((patternVal > 0.4f) && (patternVal < 0.5f))
            discard;
        }

        fragColor = DestinationColor;
    }
    else if (FragmentType == 5)
    {
        highp vec4 texColor = texture(Texture, TexCoordOut.xy);
        //texColor.a *= Time * 0.004;//Time * 0.001;
        if (texColor.a<0.05)
        discard;
        fragColor = texColor;
    }
    else if (FragmentType == 20)
    {
        vec4 color = vec4(DestinationColor.rgba);
        if (Time >= 0.0) {
            color.a *= Time * 0.012;
        }

        fragColor = color;
    }
    else if (FragmentType == 22)
    {
        float regionWidth = (texRegion[2] - texRegion[0]);
        float regionHeight = (texRegion[1] - texRegion[3]);

        vec2 texCoord = vec2(
        texRegion[0] + (TexCoordOut.x * regionWidth),
        (1.0f - texRegion[1]) + (TexCoordOut.y * regionHeight));

        vec4 texColor = texture(Texture, texCoord);
        fragColor = edgeGradientTexture(texColor, texCoord);
    }
    else if (FragmentType == 23)
    {
        fragColor = edgeGradientTexture(DestinationColor, gl_PointCoord);
    }
    else if (FragmentType == 24)
    {
        float regionWidth = (texRegion[2] - texRegion[0]);
        float regionHeight = (texRegion[1] - texRegion[3]);

        vec2 texCoord = vec2(
        texRegion[0] + (TexCoordOut.x * regionWidth),
        (1.0f - texRegion[1]) + (TexCoordOut.y * regionHeight));

        vec4 texColor = texture(Texture, texCoord);
        fragColor = vec4(TexChannelColor.r, TexChannelColor.g, TexChannelColor.b, texColor.a);
    }
    else
    {
        fragColor = DestinationColor;
    }
}