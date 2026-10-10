package javax.microedition.lcdui;
public final class Font { public static final int FACE_SYSTEM=0, FACE_MONOSPACE=32, FACE_PROPORTIONAL=64, STYLE_PLAIN=0, STYLE_BOLD=1, SIZE_SMALL=8, SIZE_MEDIUM=0, SIZE_LARGE=16;
 public static Font getFont(int face,int style,int size){return null;} public static Font getDefaultFont(){return null;} public int getHeight(){return 0;} public int stringWidth(String s){return 0;}
 public int substringWidth(String s,int o,int l){return 0;} public int charWidth(char c){return 0;} }
