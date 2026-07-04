#version 300 es

precision highp float;
precision highp int;

in vec2 screenCoord;
out vec4 fragColor;

uniform sampler2D screenTex;
uniform vec2 pixelSize;

const int NUM_EDGE_STEPS = 11;
const float absoluteThreshold = 0.0312f;
const float relativeThreshold = 0.063f;
const float subpixelBlendingFactor = 1.00f;
const float EPSILON = 1e-6f;

const int
    TEXEL_IDX_ORIGIN		= 0,
    TEXEL_IDX_NORTH			= 1,
    TEXEL_IDX_SOUTH			= 2,
    TEXEL_IDX_EAST			= 3,
    TEXEL_IDX_WEST			= 4,
    TEXEL_IDX_NORTH_EAST	= 5,
    TEXEL_IDX_SOUTH_EAST	= 6,
    TEXEL_IDX_NORTH_WEST	= 7,
    TEXEL_IDX_SOUTH_WEST	= 8;

struct BlendingMeta
{
    vec2 normal;
    vec2 tangent;
    float gradient;
    float originalLuminance;
    float edgeLuminance;
};

float getGrayscaledColor(const vec3 originalColor)
{
    return dot(vec3(.2126f, .7152f, .0722f), originalColor);
}

float sampleLuminance(const vec2 texCoord)
{
    vec3 originalColor = texture(screenTex, texCoord).rgb;
    return getGrayscaledColor(originalColor);
}

void getMinMaxLuminance(const float luminances[9], out float minLuminance, out float maxLuminance)
{
    minLuminance = luminances[TEXEL_IDX_ORIGIN];
    maxLuminance = luminances[TEXEL_IDX_ORIGIN];

    for (int i = TEXEL_IDX_NORTH; i <= TEXEL_IDX_WEST; i++)
    {
        minLuminance = min(minLuminance, luminances[i]);
        maxLuminance = max(maxLuminance, luminances[i]);
    }
}

float getContrastThreshold(const float maxLuminance)
{
    return max(absoluteThreshold, relativeThreshold * maxLuminance);
}

BlendingMeta getBlendingMeta(const float luminances[9])
{
    BlendingMeta retVal;
    retVal.originalLuminance = luminances[TEXEL_IDX_ORIGIN];

    float horizontalFactor =
        ((abs(luminances[TEXEL_IDX_NORTH] + luminances[TEXEL_IDX_SOUTH] - (2.0f * luminances[TEXEL_IDX_ORIGIN])) * 2.0f) +
        abs(luminances[TEXEL_IDX_NORTH_EAST] + luminances[TEXEL_IDX_SOUTH_EAST] - (2.0f * luminances[TEXEL_IDX_EAST])) +
        abs(luminances[TEXEL_IDX_NORTH_WEST] + luminances[TEXEL_IDX_SOUTH_WEST] - (2.0f * luminances[TEXEL_IDX_WEST])));

    float verticalFactor =
        ((abs(luminances[TEXEL_IDX_EAST] + luminances[TEXEL_IDX_WEST] - (2.0f * luminances[TEXEL_IDX_ORIGIN])) * 2.0f) +
        abs(luminances[TEXEL_IDX_NORTH_EAST] + luminances[TEXEL_IDX_NORTH_WEST] - (2.0f * luminances[TEXEL_IDX_NORTH])) +
        abs(luminances[TEXEL_IDX_SOUTH_EAST] + luminances[TEXEL_IDX_SOUTH_WEST] - (2.0f * luminances[TEXEL_IDX_SOUTH])));

    bool isHorizontal = (horizontalFactor >= verticalFactor);

    if (isHorizontal)
    {
        float forwardGradient = abs(luminances[TEXEL_IDX_NORTH] - luminances[TEXEL_IDX_ORIGIN]);
        float backwardGradient = abs(luminances[TEXEL_IDX_SOUTH] - luminances[TEXEL_IDX_ORIGIN]);

        bool isForward = (forwardGradient > backwardGradient);

        if (isForward)
        {
            retVal.normal = vec2(0.0f, 1.0f);
            retVal.gradient = forwardGradient;
            retVal.edgeLuminance = ((luminances[TEXEL_IDX_ORIGIN] + luminances[TEXEL_IDX_NORTH]) * 0.5f);
        }
        else
        {
            retVal.normal = vec2(0.0f, -1.0f);
            retVal.gradient = backwardGradient;
            retVal.edgeLuminance = ((luminances[TEXEL_IDX_ORIGIN] + luminances[TEXEL_IDX_SOUTH]) * 0.5f);
        }

        retVal.tangent = vec2(1.0f, 0.0f);
    }
    else
    {
        float forwardGradient = abs(luminances[TEXEL_IDX_EAST] - luminances[TEXEL_IDX_ORIGIN]);
        float backwardGradient = abs(luminances[TEXEL_IDX_WEST] - luminances[TEXEL_IDX_ORIGIN]);

        bool isForward = (forwardGradient > backwardGradient);

        if (isForward)
        {
            retVal.normal = vec2(1.0f, 0.0f);
            retVal.gradient = forwardGradient;
            retVal.edgeLuminance = ((luminances[TEXEL_IDX_ORIGIN] + luminances[TEXEL_IDX_EAST]) * 0.5f);
        }
        else
        {
            retVal.normal = vec2(-1.0f, 0.0f);
            retVal.gradient = backwardGradient;
            retVal.edgeLuminance = ((luminances[TEXEL_IDX_ORIGIN] + luminances[TEXEL_IDX_WEST]) * 0.5f);
        }

        retVal.tangent = vec2(0.0f, 1.0f);
    }

    return retVal;
}

float getPixelBlendingFactor(const float luminances[9], const float contrast)
{
    if (contrast < EPSILON)
        return 0.0f;

    float avgLuminance = 0.0f;

    for (int i = TEXEL_IDX_NORTH; i <= TEXEL_IDX_SOUTH_WEST; i++)
        avgLuminance += (luminances[i] * ((i >= TEXEL_IDX_NORTH_EAST) ? 1.0f : 2.0f));

    avgLuminance /= 12.0f;

    float linearFactor = clamp(abs(luminances[TEXEL_IDX_ORIGIN] - avgLuminance) / contrast, 0.0f, 1.0f);
    float smoothFactor = smoothstep(0.0f, 1.0f, linearFactor);

    return ((smoothFactor * smoothFactor) * subpixelBlendingFactor);
}

float getEdgeBlendingFactor(const BlendingMeta blendingMeta, const vec2 screenCoord)
{
    float edgeStepUnits[NUM_EDGE_STEPS];
    edgeStepUnits[0] = 1.0f;
    edgeStepUnits[1] = 1.0f;
    edgeStepUnits[2] = 1.0f;
    edgeStepUnits[3] = 1.0f;
    edgeStepUnits[4] = 1.0f;
    edgeStepUnits[5] = 1.0f;
    edgeStepUnits[6] = 1.0f;
    edgeStepUnits[7] = 1.0f;
    edgeStepUnits[8] = 1.0f;
    edgeStepUnits[9] = 1.0f;
    edgeStepUnits[10] = 1.0f;

    float deltaThreshold = (blendingMeta.gradient * 0.25f);
    vec2 startingCoord = (screenCoord + (blendingMeta.normal * pixelSize * 0.5f));

    // forward search
    float forwardStep = 0.0f;
    vec2 forwardCoord = startingCoord;

    for (int stepIter = 0; stepIter < NUM_EDGE_STEPS; stepIter++)
    {
        float edgeStepUnit = edgeStepUnits[stepIter];

        forwardStep += edgeStepUnit;
        forwardCoord += (edgeStepUnit * pixelSize * blendingMeta.tangent);

        float luminanceDelta = (sampleLuminance(forwardCoord) - blendingMeta.edgeLuminance);
        if (abs(luminanceDelta) > deltaThreshold)
            break;
    }

    // backward search
    float backwardStep = 0.0f;
    vec2 backwardCoord = startingCoord;

    for (int stepIter = 0; stepIter < NUM_EDGE_STEPS; stepIter++)
    {
        float edgeStepUnit = edgeStepUnits[stepIter];

        backwardStep += edgeStepUnit;
        backwardCoord -= (edgeStepUnit * pixelSize * blendingMeta.tangent);

        float luminanceDelta = (sampleLuminance(backwardCoord) - blendingMeta.edgeLuminance);
        if (abs(luminanceDelta) > deltaThreshold)
            break;
    }

    float edgeLength = (forwardStep + backwardStep);
    if (edgeLength < EPSILON)
        return 0.0f;

    float shortestStep;

    // shortest direction == forward
    if (forwardStep <= backwardStep)
    {
        shortestStep = forwardStep;
        float luminanceDelta = (sampleLuminance(forwardCoord) - blendingMeta.edgeLuminance);

        if ((luminanceDelta >= 0.0f) == (blendingMeta.originalLuminance >= blendingMeta.edgeLuminance))
            return 0.0f;
    }
    else
    {
        shortestStep = backwardStep;
        lowp float luminanceDelta = (sampleLuminance(backwardCoord) - blendingMeta.edgeLuminance);

        if ((luminanceDelta >= 0.0f) == (blendingMeta.originalLuminance >= blendingMeta.edgeLuminance))
        return 0.0f;
    }

    return (0.5f - (shortestStep / edgeLength));
}

void main()
{
    float luminances[9];
    luminances[0] = sampleLuminance(screenCoord);                                       // origin (0)
    luminances[1] = sampleLuminance(screenCoord + vec2(0.0, pixelSize.y));              // north (1)
    luminances[2] = sampleLuminance(screenCoord + vec2(0.0, -pixelSize.y));             // south (2)
    luminances[3] = sampleLuminance(screenCoord + vec2(pixelSize.x, 0.0));              // east (3)
    luminances[4] = sampleLuminance(screenCoord + vec2(-pixelSize.x, 0.0));             // west (4)
    luminances[5] = sampleLuminance(screenCoord + pixelSize);                           // north east (5)
    luminances[6] = sampleLuminance(screenCoord + vec2(pixelSize.x, -pixelSize.y));     // south east (6)
    luminances[7] = sampleLuminance(screenCoord + vec2(-pixelSize.x, pixelSize.y));     // north west (7)
    luminances[8] = sampleLuminance(screenCoord + vec2(-pixelSize.x, -pixelSize.y));    // south west (8)

    float minLuminance;
    float maxLuminance;
    getMinMaxLuminance(luminances, minLuminance, maxLuminance);

    float contrast = (maxLuminance - minLuminance);
    float contrastThreshold = getContrastThreshold(maxLuminance);

    if (contrast < contrastThreshold)
    {
        fragColor = texture(screenTex, screenCoord);
        return;
    }

    BlendingMeta blendingMeta = getBlendingMeta(luminances);

    float pixelBlendingFactor = getPixelBlendingFactor(luminances, contrast);
    float edgeBlendingFactor = getEdgeBlendingFactor(blendingMeta, vec2(gl_FragCoord.xy) * pixelSize);
    float finalBlendingFactor = max(pixelBlendingFactor, edgeBlendingFactor);

    vec2 offset = (blendingMeta.normal * pixelSize * finalBlendingFactor);
    fragColor = texture(screenTex, screenCoord + offset);
}