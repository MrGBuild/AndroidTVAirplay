package com.mrgbuild.engine;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Queries the real multicast publisher using a separate UDP socket, not a mock. */
class MdnsWireTest {
    @Test void answersNetworkDnsQueriesWithBothReceiverServices()throws Exception {
        Inet4Address address=null;
        for(NetworkInterface nic:Collections.list(NetworkInterface.getNetworkInterfaces())){
            if(!nic.isUp()||nic.isLoopback()||!nic.supportsMulticast())continue;
            for(InetAddress candidate:Collections.list(nic.getInetAddresses()))if(candidate instanceof Inet4Address ip&&!ip.isLinkLocalAddress()){address=ip;break;}
            if(address!=null)break;
        }
        assertNotNull(address,"No multicast-capable IPv4 interface available to validate discovery");
        String name="WireProbe-"+UUID.randomUUID().toString().substring(0,6),id="02:11:22:33:44:55",pk="11".repeat(32);
        try(MdnsPublisher publisher=new MdnsPublisher(address,name,id,pk,45678);MulticastSocket query=new MulticastSocket(new InetSocketAddress(address,0))){
            query.setNetworkInterface(NetworkInterface.getByInetAddress(address));query.setTimeToLive(255);query.setSoTimeout(3000);
            for(String type:new String[]{"_airplay._tcp.local.","_raop._tcp.local."}){
                String instance=(type.startsWith("_raop")?id.replace(":","")+"@":"")+name+"."+type;
                ByteArrayOutputStream bytes=new ByteArrayOutputStream();DataOutputStream out=new DataOutputStream(bytes);
                out.writeShort(0x1234);out.writeShort(0);out.writeShort(2);out.writeShort(0);out.writeShort(0);out.writeShort(0);
                question(out,instance,16);question(out,instance,33);
                byte[] request=bytes.toByteArray();query.send(new DatagramPacket(request,request.length,InetAddress.getByName("224.0.0.251"),5353));
                boolean txt=false,srv=false;long deadline=System.nanoTime()+5_000_000_000L;
                while(System.nanoTime()<deadline&&!(txt&&srv)){
                    byte[] buffer=new byte[9000];DatagramPacket packet=new DatagramPacket(buffer,buffer.length);query.receive(packet);
                    byte[] data=Arrays.copyOf(buffer,packet.getLength());
                    if(packet.getPort()!=5353 || (u16(data,2)&0x8000)==0)continue;
                    int at=12;
                    for(int i=0;i<u16(data,4);i++)at=skipName(data,at)+4;
                    int records=u16(data,6)+u16(data,8)+u16(data,10);
                    for(int i=0;i<records;i++){
                        at=skipName(data,at);int recordType=u16(data,at),length=u16(data,at+8);at+=10;
                        assertTrue(at+length<=data.length);
                        if(recordType==16){
                            Set<String> properties=new HashSet<>();int end=at+length;
                            for(int t=at;t<end;){int n=data[t++]&255;assertTrue(t+n<=end);properties.add(new String(data,t,n,StandardCharsets.UTF_8));t+=n;}
                            txt|=properties.contains("pw=true")&&properties.contains("pk="+pk)&&properties.stream().anyMatch(x -> x.startsWith("features="));
                        }
                        if(recordType==33&&length>=6)srv|=u16(data,at+4)==45678;
                        at+=length;
                    }
                }
                assertTrue(txt,"Password/public-key/feature TXT records were not received over UDP for "+type);
                assertTrue(srv,"Actual receiver port was not received in a DNS SRV answer for "+type);
            }
        }
    }
    private static int u16(byte[] data,int at){assertTrue(at+1<data.length);return ((data[at]&255)<<8)|(data[at+1]&255);}
    private static int skipName(byte[] data,int at){
        for(int labels=0;labels<128;labels++){
            assertTrue(at<data.length);int n=data[at++]&255;if(n==0)return at;
            if((n&0xc0)==0xc0){assertTrue(at<data.length);return at+1;}
            assertTrue(n<=63&&at+n<=data.length);at+=n;
        }throw new IllegalArgumentException("Invalid DNS name");
    }
    private void question(DataOutputStream out,String name,int type)throws Exception{
        for(String label:name.split("\\.")){byte[] data=label.getBytes(StandardCharsets.UTF_8);out.writeByte(data.length);out.write(data);}out.writeByte(0);out.writeShort(type);out.writeShort(0x8001);
    }
}
