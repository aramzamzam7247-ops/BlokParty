package javax.microedition.lcdui.game;
import javax.microedition.lcdui.*;
public abstract class GameCanvas extends Canvas { public static final int UP_PRESSED=2, DOWN_PRESSED=64, LEFT_PRESSED=4, RIGHT_PRESSED=32, FIRE_PRESSED=256;
 protected GameCanvas(boolean suppress){} protected Graphics getGraphics(){return null;} public void flushGraphics(){} public int getKeyStates(){return 0;} public void paint(Graphics g){} }
