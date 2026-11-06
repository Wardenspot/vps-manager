// Интерфейс приложения «OpenVPN for Android» (de.blinkt.openvpn) для управления из других приложений.
// Порядок методов должен совпадать с оригиналом — по нему считаются номера вызовов.
package de.blinkt.openvpn.api;

import de.blinkt.openvpn.api.APIVpnProfile;
import de.blinkt.openvpn.api.IOpenVPNStatusCallback;

import android.content.Intent;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;

interface IOpenVPNAPIService {
    List<APIVpnProfile> getProfiles();
    void startProfile (String profileUUID);
    boolean addVPNProfile (String name, String config);
    void startVPN (in String inlineconfig);
    Intent prepare (in String packagename);
    Intent prepareVPNService ();
    void disconnect();
    void pause();
    void resume();
    void registerStatusCallback(in IOpenVPNStatusCallback cb);
    void unregisterStatusCallback(in IOpenVPNStatusCallback cb);
    void removeProfile (in String profileUUID);
    boolean protectSocket(in ParcelFileDescriptor fd);
    APIVpnProfile addNewVPNProfile (String name, boolean userEditable, String config);
    void startVPNwithExtras(in String inlineconfig, in Bundle extras);
    APIVpnProfile addNewVPNProfileWithExtras (String name, boolean userEditable, String config, in Bundle extras);
    APIVpnProfile getDefaultProfile();
    void setDefaultProfile (String profileUUID);
}
