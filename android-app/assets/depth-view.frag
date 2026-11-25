#version 300 es
precision highp float;
uniform highp usampler2D depthTexture;
uniform mat3 depthUvTransform;
uniform vec2 viewport;
out vec4 frag;
vec3 depthColour(ivec2 p){
  ivec2 size=textureSize(depthTexture,0);uvec2 d=texelFetch(depthTexture,clamp(p,ivec2(0),size-1),0).rg;
  if(d.r==uint(0)||d.g==uint(0))return vec3(0);
  float x=clamp((float(d.r)-200.0)/4800.0,0.0,1.0);
  vec3 c=clamp(vec3(1.5-abs(4.0*x-1.0),1.5-abs(4.0*x-2.0),1.5-abs(4.0*x-3.0)),0.0,1.0);
  return c*float(d.g)/255.0;
}
void main(){
  vec2 uv=(depthUvTransform*vec3(gl_FragCoord.xy/viewport,1)).xy;
  if(any(lessThan(uv,vec2(0)))||any(greaterThan(uv,vec2(1)))){frag=vec4(0,0,0,1);return;}
  vec2 pixel=uv*vec2(textureSize(depthTexture,0))-.5;ivec2 base=ivec2(floor(pixel));vec2 f=smoothstep(vec2(0),vec2(1),fract(pixel));
  // Display-only colour interpolation. No depth is invented or fed back to occlusion.
  vec3 lo=mix(depthColour(base),depthColour(base+ivec2(1,0)),f.x);
  vec3 hi=mix(depthColour(base+ivec2(0,1)),depthColour(base+ivec2(1,1)),f.x);
  frag=vec4(mix(lo,hi,f.y),1);
}
