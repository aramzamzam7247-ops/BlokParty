package java.lang;
public class Thread implements Runnable { public Thread(){} public Thread(Runnable r){} public synchronized void start(){} public void run(){}
 public static void sleep(long ms) throws InterruptedException{} public static void yield(){} public final void setPriority(int p){} public static final int MIN_PRIORITY=1, NORM_PRIORITY=5, MAX_PRIORITY=10; }
