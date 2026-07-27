package com.warden.controlledsandbox.contract;

import android.os.IBinder;
import com.warden.controlledsandbox.contract.IPackageManagementSession;

interface IPackageService {
    IPackageManagementSession openManagementSession(in IBinder clientToken);
}
