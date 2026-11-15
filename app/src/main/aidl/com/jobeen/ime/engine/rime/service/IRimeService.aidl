// SPDX-License-Identifier: Apache-2.0
package com.jobeen.ime.engine.rime.service;

// WO52 3.1b：跨进程部署/同步 Binder 面（:ime Service）。
// 元书链 #rimeDeploy/#rimeSync → Deploy/Sync → 跨进程 → 防重入 →
// RimeNative.startMaintenance/syncUserData 的 Jime 对位：部署与
// librime 用户数据同步都在 :ime 进程内执行，应用进程只经 Binder 调用。
// deploy()/syncUserData() 均为同步调用：执行在 Service 侧的 Binder
// 线程上阻塞直至完成（部署可达数十分钟），调用方必须在后台线程调用。
interface IRimeService {
    /** 全量部署并等完成；已有部署/同步在进行时立即返回 false（防重入）。 */
    boolean deploy();

    /** librime 用户数据同步（sync_user_data）；已有部署/同步在进行时立即返回 false。 */
    boolean syncUserData();

    /** 是否有部署/同步正在进行。 */
    boolean isBusy();
}
