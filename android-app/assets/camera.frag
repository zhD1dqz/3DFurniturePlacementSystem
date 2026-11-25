#version 300 es
#extension GL_OES_EGL_image_external_essl3 : require
precision mediump float;
uniform samplerExternalOES cameraTexture;
in vec2 texcoord;
out vec4 frag;
void main(){frag=texture(cameraTexture,texcoord);}
