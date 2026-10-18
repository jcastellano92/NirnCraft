package com.skycraft.client.autotest;

import com.google.gson.JsonObject;
import com.skycraft.Skycraft;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/** Native asynchronous readback; publish only complete PNGs and keep capture sequences stationary until written. */
public final class ReviewCaptures {
    private static final AtomicInteger PENDING=new AtomicInteger();
    private static final java.util.concurrent.ExecutorService WRITER=Executors.newSingleThreadExecutor(r->{
        var thread=new Thread(r,"Skycraft review capture writer");thread.setDaemon(true);return thread;
    });
    public static int pending(){return PENDING.get();}
    public static CompletableFuture<Path> capture(Minecraft mc,Path directory,String name,Consumer<Path> written){
        return capture(mc,directory,name,VisualCamera.captureReceipt(mc),written);
    }
    public static CompletableFuture<Path> capture(Minecraft mc,Path directory,String name,JsonObject receipt,Consumer<Path> written){
        var completion=new CompletableFuture<Path>();PENDING.incrementAndGet();
        try{
            Screenshot.takeScreenshot(mc.gameRenderer.mainRenderTarget(),image->WRITER.execute(()->{
                try(image){
                    Files.createDirectories(directory);Path output=directory.resolve(name+".png"),temporary=directory.resolve(name+".png.part");
                    image.writeToFile(temporary);
                    Files.move(temporary,output,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);
                    if(receipt!=null)Files.writeString(directory.resolve(name+".json"),receipt.toString());
                    if(written!=null)written.accept(output);
                    completion.complete(output);
                    Skycraft.LOGGER.info("Review capture saved {}",output.toAbsolutePath());
                }catch(Exception failure){completion.completeExceptionally(failure);Skycraft.LOGGER.error("Review capture failed: {}",name,failure);}
                finally{PENDING.decrementAndGet();}
            }));
        }catch(RuntimeException failure){PENDING.decrementAndGet();completion.completeExceptionally(failure);}
        return completion;
    }
    private ReviewCaptures(){}
}
