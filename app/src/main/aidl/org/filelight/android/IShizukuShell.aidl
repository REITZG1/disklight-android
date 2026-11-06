package org.filelight.android;

interface IShizukuShell {
    ParcelFileDescriptor runCommand(String cmd);
    String getUidInfo();
}
