.class Lcom/loadLib/libLoader$1;
.super Ljava/lang/Object;
.source "libLoader.java"

# interfaces
.implements Ljava/lang/Runnable;


# direct methods
.method constructor <init>()V
    .locals 0

    invoke-direct {p0}, Ljava/lang/Object;-><init>()V

    return-void
.end method


# virtual methods
.method public run()V
    .locals 2

    const-string v0, "avalon"

    invoke-static {v0}, Ljava/lang/System;->loadLibrary(Ljava/lang/String;)V

    # libshadowhook.so is a DT_NEEDED dependency of libfgohook.so. Loading it
    # directly would run its JNI_OnLoad, which expects ShadowHook's Java classes
    # (not present in the game). The linker loads it without calling JNI_OnLoad.
    const-string v1, "fgohook"

    invoke-static {v1}, Ljava/lang/System;->loadLibrary(Ljava/lang/String;)V

    return-void
.end method
