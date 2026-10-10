package java.lang;
public final class StringBuffer { public StringBuffer(){} public StringBuffer(String s){} public StringBuffer(int n){}
 public StringBuffer append(String s){return this;} public StringBuffer append(int i){return this;} public StringBuffer append(char c){return this;} public StringBuffer append(Object o){return this;}
 public StringBuffer append(float f){return this;} public StringBuffer append(boolean b){return this;} public StringBuffer append(long l){return this;} public StringBuffer append(char[] c){return this;}
 public String toString(){return null;} public int length(){return 0;} public void setLength(int n){} public char charAt(int i){return 0;} public StringBuffer insert(int o,String s){return this;} }
