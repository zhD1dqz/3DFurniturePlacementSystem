#version 300 es
void main(){vec2 p=vec2((gl_VertexID&1)*2-1,(gl_VertexID>>1)*2-1);gl_Position=vec4(p,0,1);}
