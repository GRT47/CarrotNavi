varying lowp vec4 DestinationColor;
varying mediump vec2 TexCoordOut;
varying mediump vec4 TexChannelColorOut;

uniform sampler2D Texture;
uniform bool UseTexture;
uniform int textureType;
uniform highp float Time;

uniform int FragmentType;
//TODO: ??? compile error 확인

mediump vec4 edgeGradientTexture(mediump vec4 texture, mediump vec2 texCoord)
{
    mediump vec4 texColor = texture;
    mediump float min = 0.1;
    mediump float max = 0.25;
    mediump float edge = smoothstep(min, max, texCoord.x) *
    smoothstep(min, max, 1.0 - texCoord.x) *
    smoothstep(min, max, texCoord.y) *
    smoothstep(min, max, 1.0 - texCoord.y);
    //    vec4 gradientColor = vec4(0.5, 0.5, 0.5, 0.5);

    mediump vec4 mixColor_s = vec4(.05, .05, .05, 0.22);
    mediump vec4 mixColor = vec4(0.05, 0.05, 0.05, 0.25);
    mediump float edgeFactor = edge;
    mediump vec4 mix = mix(mixColor_s, mixColor, edgeFactor);
    return texColor * mix;
}

void main(void)
{
    if (UseTexture)
    {
        lowp vec4 texColor = texture2D(Texture, TexCoordOut);
        texColor.a *= Time * 0.004;//Time * 0.001;
        if (texColor.a<0.05)
        discard;
        gl_FragColor = texColor;
    }
    else
    {
        if (FragmentType == 22)
        {
            lowp vec4 texColor = texture2D(Texture, TexCoordOut);
            gl_FragColor = edgeGradientTexture(texColor, TexCoordOut);
        }
        else if (FragmentType == 23)
        {
            gl_FragColor = edgeGradientTexture(DestinationColor, gl_PointCoord);
        }
        else if (FragmentType == 24)
        {
            lowp vec4 texColor = texture2D(Texture, TexCoordOut);
            gl_FragColor = vec4(TexChannelColorOut.r, TexChannelColorOut.g, TexChannelColorOut.b, texColor.a);
        }
        else
        {
            gl_FragColor = DestinationColor;
        }
    }
}