package java.io;
public class DataInputStream extends InputStream { public DataInputStream(InputStream in){} public int read() throws IOException{return 0;}
 public final void readFully(byte[] b) throws IOException{} public final void readFully(byte[] b,int o,int l) throws IOException{} public final int readUnsignedByte() throws IOException{return 0;}
 public final byte readByte() throws IOException{return 0;} public final short readShort() throws IOException{return 0;} public final int readUnsignedShort() throws IOException{return 0;}
 public final int readInt() throws IOException{return 0;} public final String readUTF() throws IOException{return null;} public final boolean readBoolean() throws IOException{return false;} }
