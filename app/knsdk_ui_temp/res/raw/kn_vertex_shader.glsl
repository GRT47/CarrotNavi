attribute vec4 Position;
attribute vec2 TrafficGapVector;
attribute vec2 TrafficWidthVector;
attribute vec4 SourceColor;
attribute vec2 TexCoordIn;
attribute float ScaleColor;

varying vec4 DestinationColor;
varying vec2 TexCoordOut;
varying vec4 TexChannelColorOut;

uniform mat4 Ortho;
uniform mat4 Projection;
uniform mat4 Camera;
uniform mat4 Model;
uniform float ScaleAlpha;
uniform float TrafficGap;
uniform float TrafficWidth;
uniform float BuildingDegree;
uniform float RgArrowHeight;

uniform bool RemoveRouteLineAlpha;
uniform bool UseScaleColor;
uniform int VertexType;
uniform vec4 TexChannelColor;

void main(void)
{
    TexCoordOut = TexCoordIn;

    if (UseScaleColor)
    {
        DestinationColor.rgb = SourceColor.rgb * ScaleColor;
        DestinationColor.a = SourceColor.a * ScaleAlpha;

        if (RemoveRouteLineAlpha)
        {
            if (DestinationColor.rgb == vec3(0.0)) {
                DestinationColor.a = 0.0;
            }
        }
    }
    else
    {
        DestinationColor = SourceColor;
    }

    if (VertexType == 1)
    {
        gl_Position = Ortho * Model * Position;
    }
    else if (VertexType == 2)
    {
        vec4 _position;
        _position = Position;
        _position.xy += TrafficGap*TrafficGapVector + TrafficWidth*TrafficWidthVector;
        gl_Position = Projection * Camera * Model * _position;
    }
    else if (VertexType == 3)
    {
        vec4 _position;
        _position = Position;
        _position.xy += (TrafficWidth*TrafficWidthVector);
        gl_Position = Projection * Camera * Model * _position;
    }
    else if (VertexType == 31)
    {
        TexChannelColorOut = TexChannelColor;
        vec4 _position;
        _position = vec4(Position.x, Position.y, RgArrowHeight, Position.w);
        _position.xy += (TrafficWidth * TrafficWidthVector);
        gl_Position = Projection * Camera * Model * _position;
    }
    else
    {
        vec4 pos;
        if (Position.z > 0.0) {
            float z = Position.z * BuildingDegree;
            pos = vec4(Position.x, Position.y, z, Position.w);
        } else {
            pos = Position;
        }
        gl_Position = Projection * Camera * Model * pos;//Position;
    }
}