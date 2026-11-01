package com.mrgbuild.engine;
import java.net.*;
import java.util.Arrays;
/** RAOP NTP timing replies; no independent outbound connections. */
public final class TimingServer implements AutoCloseable {
    private final DatagramSocket socket;
    public TimingServer() throws SocketException {
        socket=new DatagramSocket(0);
        Thread thread=new Thread(() -> {
            byte[] data=new byte[256];
            while(!socket.isClosed())try{
                DatagramPacket input=new DatagramPacket(data,data.length);socket.receive(input);
                if(input.getLength()!=32 || (data[1]&0x7f)!=0x52)continue;
                byte[] reply=new byte[32];reply[0]=(byte)0x80;reply[1]=(byte)0xd3;reply[2]=data[2];reply[3]=data[3];
                System.arraycopy(data,24,reply,8,8);timestamp(reply,16);timestamp(reply,24);
                socket.send(new DatagramPacket(reply,reply.length,input.getSocketAddress()));
            }catch(Exception ignored){if(socket.isClosed())break;}
        },"airplay-timing");thread.setDaemon(true);thread.start();
    }
    public int port(){return socket.getLocalPort();}
    private static void timestamp(byte[] data,int at){
        long millis=System.currentTimeMillis(),seconds=millis/1000+2208988800L,fraction=(millis%1000)*0x100000000L/1000;
        for(int i=3;i>=0;i--){data[at+i]=(byte)seconds;seconds>>>=8;data[at+4+i]=(byte)fraction;fraction>>>=8;}
    }
    public void close(){socket.close();}
}
