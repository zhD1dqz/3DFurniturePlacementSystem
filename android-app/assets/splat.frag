#version 300 es
precision highp float;
in vec2 gaussian;
in vec4 colour;
in float viewDepth;
flat in mat3 precisionView;
flat in vec3 splatViewCentre;
flat in float sigmaDepth;
uniform highp usampler2D depthTexture;
uniform bool useDepth;
uniform mat3 depthUvTransform;
uniform vec2 viewport;
uniform mat4 inverseProjection;
uniform bool smoothEdges;
out vec4 frag;
float visibilityAt(ivec2 pixel,float assetDepth){
  ivec2 size=textureSize(depthTexture,0);uvec2 sampleDepth=texelFetch(depthTexture,clamp(pixel,ivec2(0),size-1),0).rg;
  float realDepth=float(sampleDepth.r)*.001;
  if(realDepth<.15||realDepth>8.0||sampleDepth.g==uint(0))return 1.0;
  float tolerance=max(.02,.015*assetDepth);
  return smoothstep(-tolerance,tolerance,realDepth-assetDepth);
}
void main() {
  float a=min(.99,colour.a*exp(-.5*dot(gaussian,gaussian)));
  float effectiveDepth=viewDepth;
  if(useDepth){
    vec2 uv=(depthUvTransform*vec3(gl_FragCoord.xy/viewport,1.0)).xy;
    if(all(greaterThanEqual(uv,vec2(0)))&&all(lessThan(uv,vec2(1)))){
      vec2 p=uv*vec2(textureSize(depthTexture,0))-.5;ivec2 base=ivec2(floor(p));
      float visible;
      if(smoothEdges){
        vec2 f=smoothstep(vec2(.15),vec2(.85),fract(p));
        // Interpolate visibility, never average foreground/background depths.
        float lo=mix(visibilityAt(base,effectiveDepth),visibilityAt(base+ivec2(1,0),effectiveDepth),f.x);
        float hi=mix(visibilityAt(base+ivec2(0,1),effectiveDepth),visibilityAt(base+ivec2(1,1),effectiveDepth),f.x);
        visible=smoothstep(.15,.85,mix(lo,hi,f.y));
      }else visible=visibilityAt(ivec2(floor(uv*vec2(textureSize(depthTexture,0)))),effectiveDepth);
      a*=visible;
    }
  }
  if(a<1.0/255.0) discard;
  frag=vec4(colour.rgb*a,a);
}
