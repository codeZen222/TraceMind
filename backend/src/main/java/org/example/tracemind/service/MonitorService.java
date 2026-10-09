package org.example.tracemind.service;

import org.example.tracemind.pojo.vo.MonitorStatusVO;

public interface MonitorService {

    /** 开启监听。已开启时幂等返回当前状态 */
    MonitorStatusVO start(String projectId);

    /** 停止监听。offset 保留，下次 start 接着读 */
    MonitorStatusVO stop(String projectId);

    /** 查状态。没有任务时返回 running=false 的默认对象，不抛 404 */
    MonitorStatusVO status(String projectId);
}