package com.nirncraft.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import java.util.Arrays;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderType;

/** Immutable native vertices captured during extraction for procedural ship and flock geometry. */
public record CapturedGeometry(float[] attributes,int[] packed,int count) {
    private static final int FLOATS=11,INTS=3;
    public void submit(PoseStack pose,SubmitNodeCollector collector,RenderType type) {
        if(count==0)return;
        collector.submitCustomGeometry(pose,type,(transform,out)->{
            for(int v=0;v<count;v++) {
                int f=v*FLOATS,i=v*INTS;
                out.addVertex(transform,attributes[f],attributes[f+1],attributes[f+2]).setColor(packed[i]).setUv(attributes[f+3],attributes[f+4])
                    .setOverlay(packed[i+1]).setLight(packed[i+2]).setNormal(transform,attributes[f+5],attributes[f+6],attributes[f+7]);
            }
        });
    }
    public static final class Builder implements VertexConsumer {
        private float[] attributes=new float[256*FLOATS];private int[] packed=new int[256*INTS];private int count;
        private int f(){return (count-1)*FLOATS;}private int i(){return (count-1)*INTS;}
        @Override public VertexConsumer addVertex(float x,float y,float z) {
            if(count*FLOATS==attributes.length){attributes=Arrays.copyOf(attributes,attributes.length*2);packed=Arrays.copyOf(packed,packed.length*2);}
            count++;int f=f(),i=i();attributes[f]=x;attributes[f+1]=y;attributes[f+2]=z;attributes[f+6]=1;attributes[f+10]=1;
            packed[i]=-1;packed[i+2]=0xf000f0;return this;
        }
        @Override public VertexConsumer setColor(int r,int g,int b,int a){return setColor((a<<24)|(r<<16)|(g<<8)|b);}
        @Override public VertexConsumer setColor(int argb){packed[i()]=argb;return this;}
        @Override public VertexConsumer setUv(float u,float v){attributes[f()+3]=u;attributes[f()+4]=v;return this;}
        @Override public VertexConsumer setUv1(int u,int v){packed[i()+1]=(v<<16)|u;return this;}
        @Override public VertexConsumer setUv2(int u,int v){packed[i()+2]=(v<<16)|u;return this;}
        @Override public VertexConsumer setUv3(float u,float v){attributes[f()+8]=u;attributes[f()+9]=v;return this;}
        @Override public VertexConsumer setNormal(float x,float y,float z){attributes[f()+5]=x;attributes[f()+6]=y;attributes[f()+7]=z;return this;}
        @Override public VertexConsumer setLineWidth(float width){attributes[f()+10]=width;return this;}
        public CapturedGeometry build(){return new CapturedGeometry(Arrays.copyOf(attributes,count*FLOATS),Arrays.copyOf(packed,count*INTS),count);}
    }
}
