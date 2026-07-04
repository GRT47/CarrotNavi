#version 300 es

precision highp float;
precision highp int;

in vec4 Position;
in float LineLength;
in vec2 TrafficGapVector;
in vec2 TrafficWidthVector;
in vec4 SourceColor;
in vec2 TexCoordIn;
in float ScaleColor;
in vec2 vUv;

out vec4 DestinationColor;
out vec2 TexCoordOut;
out float LinePatternSeed;

out vec2 vUvOut;

uniform vec4 ScreenRegion;
uniform mat4 Ortho;
uniform mat4 Projection;
uniform mat4 Camera;
uniform mat4 Model;
uniform float ScaleAlpha;
uniform bool RemoveRouteLineAlpha;
uniform float LinePatternScale;
uniform float TrafficGap;
uniform float TrafficWidth;
uniform float BuildingDegree;
uniform float HeightMultiply;
uniform float RgArrowHeight;

uniform bool UseScaleColor;
uniform int VertexType;

const int INSIDE = 0;
const int LEFT = 1;
const int RIGHT = 2;
const int TOP = 8;
const int BOTTOM = 4;

int comptePointIntersectsType(float x, float y, float xMin, float xMax, float yMin, float yMax)
{
    int state = INSIDE;
    if (x < xMin)
    {
        state = state | RIGHT;
    }
    else if (x > xMax)
    {
        state = state | RIGHT;
    }

    if (y < yMin)
    {
        state = state | BOTTOM;
    }
    else if (y > yMax)
    {
        state = state | TOP;
    }
    return state;
}

vec4 clipToLine(vec4 ScreenRegion, vec2 position, vec2 subPos)
{
    vec2 first = position;
    vec2 second = subPos;
    int inFirstType = comptePointIntersectsType(first.x, first.y, ScreenRegion[0], ScreenRegion[2], ScreenRegion[1], ScreenRegion[3]);
    int inSecondType = comptePointIntersectsType(second.x, second.y, ScreenRegion[0], ScreenRegion[2], ScreenRegion[1], ScreenRegion[3]);
    bool pass = false;
    int count = 50;
    float x0 = first.x;
    float x1 = second.x;
    float y0 = first.y;
    float y1 = second.y;

    while (count > 0)
    {
        count--;
        if ((inFirstType | inSecondType) == INSIDE)
        {
            pass = true;
            break;
        }
        else if ((inFirstType & inSecondType) != INSIDE)
        {
            break;
        }
        else
        {
            float x = 0.0;
            float y = 0.0;
            int inType = 0;
            if (inFirstType != INSIDE)
            {
                inType = inFirstType;
            } else
            {
                inType = inSecondType;
            }

            if ((inType & TOP) != INSIDE)
            {
                x = first.x + (second.x - first.x) * (ScreenRegion[3] - first.y) / (second.y - first.y);
                y = ScreenRegion[3];
            }
            else if ((inType & BOTTOM) != INSIDE)
            {
                x = first.x + (second.x - first.x) * (ScreenRegion[1] - first.y) / (second.y - first.x);
                y = ScreenRegion[1];
            }
            else if ((inType | RIGHT) != INSIDE)
            {
                y = first.y + (second.y - first.y) * (ScreenRegion[2] - first.x) / (second.x - first.x);
                x = ScreenRegion[2];
            }
            else
            {
                y = first.y + (second.y - first.y) * (ScreenRegion[0] - first.x) / (second.x - first.x);
                x = ScreenRegion[0];
            }

            if (inType == inFirstType)
            {
                x0 = x;
                y0 = y;
                inFirstType = comptePointIntersectsType(x0, y0, ScreenRegion[0], ScreenRegion[2], ScreenRegion[1], ScreenRegion[3]);
            }
            else
            {
                x1 = x;
                y1 = y;
                inSecondType = comptePointIntersectsType(x1, y1, ScreenRegion[0], ScreenRegion[2], ScreenRegion[1], ScreenRegion[3]);
            }
        }
    }

    return vec4(x0 ,y0, x1, y1);
}


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
        _position.xy += (TrafficWidth * TrafficWidthVector);
        gl_Position = Projection * Camera * Model * _position;
    }
    else if (VertexType == 14)
    {
        vec4 _position;
        _position = Position;
        _position.xy += (TrafficWidth * TrafficWidthVector);
        LinePatternSeed = (LineLength / LinePatternScale);
        gl_Position = Projection * Camera * Model * _position;
    }
    else if (VertexType == 31)
    {
        vec4 _position;
        _position = vec4(Position.x, Position.y, RgArrowHeight, Position.w);
        _position.xy += (TrafficWidth * TrafficWidthVector).xy;
        gl_Position = Projection * Camera * Model * _position;
    }
    else
    {
        vec4 pos;
        if (Position.z > 0.0) {
            float z = Position.z * BuildingDegree;
            pos = vec4(Position.x, Position.y, z, Position.w);
            pos.z *= HeightMultiply;
        } else {
            pos = Position;
        }

        LinePatternSeed = (LineLength / LinePatternScale);
        gl_Position = Projection * Camera * Model * pos;//Position;
    }
}