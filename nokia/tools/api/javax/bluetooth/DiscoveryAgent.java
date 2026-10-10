package javax.bluetooth;
public class DiscoveryAgent { public static final int NOT_DISCOVERABLE=0, GIAC=0x9E8B33, LIAC=0x9E8B00, CACHED=0, PREKNOWN=1;
 public RemoteDevice[] retrieveDevices(int o){return null;} public boolean startInquiry(int a, DiscoveryListener l) throws BluetoothStateException{return true;}
 public boolean cancelInquiry(DiscoveryListener l){return true;} public int searchServices(int[] attrs, UUID[] uuids, RemoteDevice d, DiscoveryListener l) throws BluetoothStateException{return 0;} }
