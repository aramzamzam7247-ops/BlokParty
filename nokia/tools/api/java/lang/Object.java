package java.lang;
public class Object { public Object(){} public final native Class getClass(); public native int hashCode(); public boolean equals(Object o){return false;} public String toString(){return null;}
 public final native void notify(); public final native void notifyAll(); public final native void wait(long t) throws InterruptedException; public final void wait() throws InterruptedException{} }
