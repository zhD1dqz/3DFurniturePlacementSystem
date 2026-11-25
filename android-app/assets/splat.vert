#version 300 es
precision highp float;
layout(location=0) in vec2 corner;
layout(location=1) in vec3 centre;
layout(location=2) in vec3 diagonal;
layout(location=3) in vec3 offdiag;
layout(location=4) in vec4 dcOpacity;
layout(location=5) in vec3 sh1;
layout(location=6) in vec3 sh2;
layout(location=7) in vec3 sh3;
layout(location=8) in vec3 sh4;
layout(location=9) in vec3 sh5;
layout(location=10) in vec3 sh6;
layout(location=11) in vec3 sh7;
layout(location=12) in vec3 sh8;
layout(location=13) in float furnitureIndex;
uniform bool multiScene;
uniform highp sampler2D objectTransforms;
uniform mat4 viewModel;
uniform mat4 projection;
uniform mat3 metricToRawRotation;
uniform vec3 eyeLocal;
uniform vec2 viewport;
uniform bool scenePreview;
out vec2 gaussian;
out vec4 colour;
out float viewDepth;
flat out mat3 precisionView;
flat out vec3 splatViewCentre;
flat out float sigmaDepth;
void main() {
  mat4 modelView=viewModel;mat3 rawRotation=metricToRawRotation;vec3 localEye=eyeLocal;
  if(multiScene){
    int k=int(furnitureIndex+.5);
    modelView=mat4(texelFetch(objectTransforms,ivec2(0,k),0),texelFetch(objectTransforms,ivec2(1,k),0),texelFetch(objectTransforms,ivec2(2,k),0),texelFetch(objectTransforms,ivec2(3,k),0));
    vec4 c0=texelFetch(objectTransforms,ivec2(4,k),0),c1=texelFetch(objectTransforms,ivec2(5,k),0),c2=texelFetch(objectTransforms,ivec2(6,k),0);
    rawRotation=mat3(c0.xyz,c1.xyz,c2.xyz);localEye=vec3(c0.w,c1.w,c2.w);
  }
  vec4 p = modelView * vec4(centre,1.0);
  viewDepth = -p.z;
  gaussian = corner * 3.0;
  if(p.z > -0.03) { gl_Position=vec4(2.0,2.0,2.0,1.0); colour=vec4(0); return; }
  mat3 cov=mat3(diagonal.x, offdiag.x,offdiag.y, offdiag.x,diagonal.y,offdiag.z, offdiag.y,offdiag.z,diagonal.z);
  mat3 w=mat3(modelView);
  cov=w*cov*transpose(w);
  precisionView=inverse(cov+mat3(1e-8));
  splatViewCentre=p.xyz;sigmaDepth=sqrt(max(cov[2][2],1e-8));
  float z=-p.z;
  float fx=projection[0][0]*viewport.x*.5;
  float fy=projection[1][1]*viewport.y*.5;
  // Match the training renderer's frustum-clamped Jacobian for full scenes.
  // Off-screen, near-camera splats otherwise expand across the entire viewport.
  vec2 slope=p.xy/z;
  if(scenePreview) slope=clamp(slope,-1.3/vec2(projection[0][0],projection[1][1]),1.3/vec2(projection[0][0],projection[1][1]));
  vec3 jx=vec3(fx/z,0.0,fx*slope.x/z);
  vec3 jy=vec3(0.0,fy/z,fy*slope.y/z);
  float a=dot(jx,cov*jx)+.3;
  float b=dot(jx,cov*jy);
  float c=dot(jy,cov*jy)+.3;
  float mid=.5*(a+c), radius=sqrt(max(0.0,.25*(a-c)*(a-c)+b*b));
  float l1=max(mid+radius,.01), l2=max(mid-radius,.01);
  vec2 axis=abs(b)>1e-6 ? normalize(vec2(b,l1-a)) : (a>=c ? vec2(1,0) : vec2(0,1));
  vec2 delta=3.0*(corner.x*sqrt(l1)*axis+corner.y*sqrt(l2)*vec2(-axis.y,axis.x));
  vec4 clip=projection*p;
  clip.xy+=2.0*delta/viewport*clip.w;
  gl_Position=clip;
  // Graphdeco/Brush SH uses the direction from camera to Gaussian.
  vec3 d=normalize(rawRotation*(centre-localEye));
  float x=d.x,y=d.y,t=d.z;
  vec3 rgb=.5+.28209479177387814*dcOpacity.rgb
    -.4886025119029199*y*sh1+.4886025119029199*t*sh2-.4886025119029199*x*sh3
    +1.0925484305920792*x*y*sh4-1.0925484305920792*y*t*sh5
    +.31539156525252005*(2.0*t*t-x*x-y*y)*sh6
    -1.0925484305920792*x*t*sh7+.5462742152960396*(x*x-y*y)*sh8;
  colour=vec4(max(rgb,vec3(0)),dcOpacity.a);
}
