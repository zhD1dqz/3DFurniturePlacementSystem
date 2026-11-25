package com.furniture.chairdemo;

import java.nio.ByteBuffer;

/** Copy both bytes of each DEPTH16 pixel, respecting row and pixel strides. */
final class DepthUpload {
    static void copy(ByteBuffer input,int width,int height,int rowStride,int pixelStride,ByteBuffer output){
        int base=input.position();output.clear();
        for(int y=0;y<height;y++)for(int x=0;x<width;x++){
            int offset=base+y*rowStride+x*pixelStride;
            output.put(input.get(offset));output.put(input.get(offset+1));
        }
        output.flip();
    }
}
