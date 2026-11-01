package com.mrgbuild.engine;

/** Manual independent-client fixture; synthetic identity and password only. */
public final class PinInteropHost {
    public static void main(String[] args)throws Exception {
        EngineWireTest host=new EngineWireTest();host.start();
        Runtime.getRuntime().addShutdownHook(new Thread(host::close));
        System.out.println("PIN_INTEROP_PORT="+host.receiver.port());
        new java.util.concurrent.CountDownLatch(1).await();
    }
}
