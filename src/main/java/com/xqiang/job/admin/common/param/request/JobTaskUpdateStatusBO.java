package com.xqiang.job.admin.common.param.request;

import lombok.Data;

/**
 * 更新任务状态
 *
 * @author mengq
 */
@Data
public class JobTaskUpdateStatusBO extends JobBaseOperateBO {

    /**
     * ID
     */
    private Integer id;

    /**
     * 状态
     */
    private Integer status;
}
