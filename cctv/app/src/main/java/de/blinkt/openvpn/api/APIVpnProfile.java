package de.blinkt.openvpn.api;

import android.os.Parcel;
import android.os.Parcelable;

/** Профиль из приложения «OpenVPN for Android». Формат совпадает с оригиналом (uuid, имя, флаг). */
public class APIVpnProfile implements Parcelable {
    public final String mUUID;
    public final String mName;
    public final boolean mUserEditable;

    public APIVpnProfile(Parcel in) {
        mUUID = in.readString();
        mName = in.readString();
        mUserEditable = in.readInt() != 0;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeString(mUUID);
        dest.writeString(mName);
        dest.writeInt(mUserEditable ? 0 : 1);
    }

    public static final Creator<APIVpnProfile> CREATOR = new Creator<APIVpnProfile>() {
        public APIVpnProfile createFromParcel(Parcel in) {
            return new APIVpnProfile(in);
        }

        public APIVpnProfile[] newArray(int size) {
            return new APIVpnProfile[size];
        }
    };
}
