package com.xqiang.job.admin.core.service.impl;

import cn.hutool.core.date.DateUtil;
import cn.hutool.core.io.FileUtil;
import cn.hutool.system.JavaRuntimeInfo;
import cn.hutool.system.OsInfo;
import cn.hutool.system.RuntimeInfo;
import cn.hutool.system.SystemUtil;
import com.alibaba.fastjson.JSON;
import com.xqiang.job.admin.common.enums.ScheduledJobStatusEnum;
import com.xqiang.job.admin.common.enums.SchedulerJobLogTypeEnum;
import com.xqiang.job.admin.common.enums.SysExceptionEnum;
import com.xqiang.job.admin.common.exception.JobAdminExceptionJobAdmin;
import com.xqiang.job.admin.common.param.dto.JobTaskPageQueryDTO;
import com.xqiang.job.admin.common.param.request.*;
import com.xqiang.job.admin.common.param.response.HomeResultVO;
import com.xqiang.job.admin.common.param.response.ScheduledQuartzJobDetailVO;
import com.xqiang.job.admin.common.param.response.ScheduledQuartzJobPageVO;
import com.xqiang.job.admin.common.util.IpAddressUtil;
import com.xqiang.job.admin.common.util.JobAdminBeanCopyUtil;
import com.xqiang.job.admin.common.util.JobAdminPageUtils;
import com.xqiang.job.admin.common.util.JobAdminStringUtils;
import com.xqiang.job.admin.common.util.compare.CompareObjectUtil;
import com.xqiang.job.admin.core.config.BasicJobConfig;
import com.xqiang.job.admin.core.dao.bean.ScheduledQuartzJobInfo;
import com.xqiang.job.admin.core.dao.bean.ScheduledQuartzJobLogInfo;
import com.xqiang.job.admin.core.dao.mapper.ScheduledQuartzJobLogMapper;
import com.xqiang.job.admin.core.dao.mapper.ScheduledQuartzJobMapper;
import com.xqiang.job.admin.core.quartz.QuartzSchedulerUtil;
import com.xqiang.job.admin.core.service.ScheduledQuartzJobService;
import org.apache.commons.collections.CollectionUtils;
import org.quartz.CronExpression;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.lang.management.ThreadMXBean;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 任务管理接口
 *
 * @author mengq
 * @version 1.0
 */
@Service
public class ScheduledQuartzJobServiceImpl implements ScheduledQuartzJobService {

    private static Logger log = LoggerFactory.getLogger(ScheduledQuartzJobServiceImpl.class);

    @Resource
    private Environment environment;

    @Resource
    private BasicJobConfig basicJobConfig;

    @Resource
    private ScheduledQuartzJobMapper scheduledQuartzJobMapper;

    @Resource
    private ScheduledQuartzJobLogMapper scheduledQuartzJobLogMapper;

    private final ReentrantLock reentrantLock = new ReentrantLock();

    /**
     * 主页统计与系统信息
     *
     * @return 主页统计与系统信息
     * @author mengq
     */
    @Override
    public HomeResultVO getHomeCount() {
        //项目key
        String projectKey = this.getProjectKeyKey();
        HomeResultVO result = new HomeResultVO();
        result.setCurrentTime(DateUtil.formatDateTime(new Date()));
        //总任务数
        result.setJobTotal(scheduledQuartzJobMapper.countByProjectAndStatus(projectKey, null));
        //已启动
        result.setEnableJobTotal(scheduledQuartzJobMapper.countByProjectAndStatus(projectKey, ScheduledJobStatusEnum.ON.getValue()));
        //已停止
        result.setStopJobTotal(scheduledQuartzJobMapper.countByProjectAndStatus(projectKey, ScheduledJobStatusEnum.OFF.getValue()));
        //启动端口
        result.setPort(environment.getProperty("local.server.port"));
        //虚拟机启动时间
        result.setStartTime(DateUtil.formatDateTime(new Date(SystemUtil.getRuntimeMXBean().getStartTime())));
        //操作系统信息
        OsInfo osInfo = SystemUtil.getOsInfo();
        //操作系统名称
        result.setSystemName(osInfo.getName());
        //系统版本
        result.setSystemVersion(osInfo.getVersion());
        //系统用户
        result.setSystemUser(SystemUtil.getUserInfo().getName());
        //线程信息
        ThreadMXBean threadMxBean = SystemUtil.getThreadMXBean();
        result.setActiveThreadCount(threadMxBean.getThreadCount());
        result.setDaemonThreadCount(threadMxBean.getDaemonThreadCount());
        result.setPeakThreadCount(threadMxBean.getPeakThreadCount());
        //java运行时信息
        JavaRuntimeInfo javaRuntimeInfo = SystemUtil.getJavaRuntimeInfo();
        //java版本
        result.setJavaVersion(javaRuntimeInfo.getVersion());
        //系统运行时信息
        RuntimeInfo runtimeInfo = SystemUtil.getRuntimeInfo();
        //总内存
        result.setMaxMemory(FileUtil.readableFileSize(runtimeInfo.getMaxMemory()));
        //总可用内存
        result.setFreeMemory(FileUtil.readableFileSize(runtimeInfo.getFreeMemory()));
        //总分配空间
        result.setTotalMemory(FileUtil.readableFileSize(runtimeInfo.getTotalMemory()));
        //已用内存
        result.setUsedMemory(FileUtil.readableFileSize(runtimeInfo.getUsableMemory()));
        return result;
    }

    /**
     * 分页任务列表
     *
     * @param queryBO 参数
     * @return 任务列表
     * @author mengq
     */
    @Override
    public ScheduledQuartzJobPageVO listPageJob(JobTaskPageQueryBO queryBO) {
        //项目key
        String projectKey = this.getProjectKeyKey();

        //构建查询参数
        JobTaskPageQueryDTO pageQueryDTO = JobTaskPageQueryDTO.builder()
                .projectKey(projectKey)
                .limit(JobAdminPageUtils.getStartRow(queryBO.getPage(), queryBO.getLimit()))
                .pageSize(JobAdminPageUtils.getOffset(queryBO.getLimit()))
                .jobNameLike(queryBO.getJobNameLike())
                .jobMethodLike(queryBO.getJobMethodLike())
                .jobStatus(queryBO.getJobStatus()).build();

        Integer total = scheduledQuartzJobMapper.countByCondition(pageQueryDTO);
        if (total <= 0) {
            return ScheduledQuartzJobPageVO.initDefault();
        }

        List<ScheduledQuartzJobInfo> jobList = scheduledQuartzJobMapper.listPageByCondition(pageQueryDTO);
        if (CollectionUtils.isEmpty(jobList)) {
            return ScheduledQuartzJobPageVO.initDefault();
        }

        return new ScheduledQuartzJobPageVO(total, this.buildScheduledQuartzJobList(jobList));
    }

    /**
     * 构建任务分页列表
     *
     * @param jobList 参数
     * @return 列表
     */
    private List<ScheduledQuartzJobDetailVO> buildScheduledQuartzJobList(List<ScheduledQuartzJobInfo> jobList) {
        ScheduledQuartzJobDetailVO quartzJobItem = null;
        List<ScheduledQuartzJobDetailVO> resultList = new ArrayList<>(jobList.size());
        for (ScheduledQuartzJobInfo jobInfo : jobList) {
            quartzJobItem = JobAdminBeanCopyUtil.copy(jobInfo, ScheduledQuartzJobDetailVO.class);
            //默认已关闭
            quartzJobItem.setJobStatus(ScheduledJobStatusEnum.OFF.getValue());
            //如果库里状态为已启动，并且Quartz 任务已经启动
            if (ScheduledJobStatusEnum.ON.getValue().equals(jobInfo.getJobStatus()) &&
                    QuartzSchedulerUtil.isStart(jobInfo)) {
                quartzJobItem.setJobStatus(ScheduledJobStatusEnum.ON.getValue());
            }
            quartzJobItem.setLastRunTime("");
            if (jobInfo.getLastRunTimestamp() > 0) {
                quartzJobItem.setLastRunTime(DateUtil.formatDateTime(new Date(jobInfo.getLastRunTimestamp())));
            }
            resultList.add(quartzJobItem);
        }

        return resultList;
    }

    /**
     * 任务详情
     *
     * @param operateBO 参数
     * @return 任务详情
     * @author mengq
     */
    @Override
    public ScheduledQuartzJobDetailVO getJobDetail(JobTaskOperateBO operateBO) {
        //项目key
        String projectKey = this.getProjectKeyKey();

        //根据任务ID查询任务
        ScheduledQuartzJobInfo jobInfo = this.getJobByProjectKeyAndId(projectKey, operateBO.getId());

        return JobAdminBeanCopyUtil.copy(jobInfo, ScheduledQuartzJobDetailVO.class);
    }

    /**
     * 新增任务
     *
     * @param jobSaveBO 新增参数
     * @author mengq
     */
    @Override
    public void addJob(JobTaskSaveBO jobSaveBO) {
        //项目key
        String projectKey = this.getProjectKeyKey();
        reentrantLock.lock();
        try {
            //构建新增参数
            ScheduledQuartzJobInfo jobInfo = this.buildScheduledQuartzJobInfo(projectKey, jobSaveBO);

            //新增前置校验
            this.checkBeforeAdd(projectKey, jobInfo);

            //新增任务默认为关闭状态 需要手工开启
            jobInfo.setJobStatus(ScheduledJobStatusEnum.OFF.getValue());
            //添加任务
            scheduledQuartzJobMapper.addByJob(jobInfo);

            //异步添加操作日志
            CompletableFuture.runAsync(() -> this.addLog(jobInfo.getId(), SchedulerJobLogTypeEnum.CREATE, jobSaveBO));

            log.info("TaskJobServiceImpl >> addJob end  id:{},operate:{}", jobInfo.getId(), jobSaveBO.getOperateName());
        } finally {
            reentrantLock.unlock();
            log.debug("新增job , 释放锁 >> addJob >> param={}", JSON.toJSONString(jobSaveBO));
        }
    }


    /**
     * 修改任务
     *
     * @param updateBO 参数
     */
    @Override
    public void updateJob(JobTaskUpdateBO updateBO) {
        //项目key
        String projectKey = this.getProjectKeyKey();
        reentrantLock.lock();
        try {
            //构建修改参数
            ScheduledQuartzJobInfo jobInfo = this.buildScheduledQuartzJobInfo(projectKey, updateBO);
            jobInfo.setId(updateBO.getId());
            //基本参数校验
            this.checkBaseBeforeUpdate(jobInfo, updateBO);
            //根据任务ID查询旧任务
            ScheduledQuartzJobInfo oldJob = this.getJobByProjectKeyAndId(projectKey, updateBO.getId());

            //是否存在变更-以及重复性校验
            this.checkBeforeUpdate(projectKey, jobInfo, oldJob, updateBO);

            //任务类方法存在校验
            QuartzSchedulerUtil.checkBeanAndMethodExists(jobInfo.getJobClass(), jobInfo.getJobMethod(), jobInfo.getJobArguments());

            //旧任务是否已经启动
            boolean oldStartFlg = QuartzSchedulerUtil.isStart(oldJob);

            //先终止掉目前调度器内的任务
            QuartzSchedulerUtil.disable(oldJob);
            scheduledQuartzJobMapper.updateByProjectAndId(jobInfo);

            //如果已经在运行的任务-则启动
            if (ScheduledJobStatusEnum.ON.getValue().equals(oldJob.getJobStatus()) && oldStartFlg) {
                QuartzSchedulerUtil.enable(jobInfo);
            }

            //异步添加操作日志
            CompletableFuture.runAsync(() -> this.addLogAfterUpdate(updateBO.getId(), oldJob, updateBO));
        } finally {
            reentrantLock.unlock();
            log.debug("修改job , 释放锁 >> updateJob >> param={}", JSON.toJSONString(updateBO));
        }
    }


    /**
     * 删除任务
     *
     * @param operateBO 参数
     */
    @Override
    public void deleteJob(JobTaskOperateBO operateBO) {
        //项目key
        String projectKey = this.getProjectKeyKey();
        reentrantLock.lock();
        try {
            //根据任务ID查询任务
            ScheduledQuartzJobInfo jobInfo = this.getJobByProjectKeyAndId(projectKey, operateBO.getId());

            //先停止任务
            QuartzSchedulerUtil.disable(jobInfo);
            //逻辑删除
            scheduledQuartzJobMapper.removeByProjectAndId(operateBO.getId(), projectKey, operateBO.getOperateBy(), operateBO.getOperateName());
            //异步添加操作日志
            CompletableFuture.runAsync(() -> this.addLog(operateBO.getId(), SchedulerJobLogTypeEnum.DELETE, operateBO));

            log.info("TaskJobServiceImpl >> deleteJob end  id:{},operate:{}", operateBO.getId(), operateBO.getOperateName());
        } finally {
            reentrantLock.unlock();
            log.debug("删除job , 释放锁 >> deleteJob >> param={}", JSON.toJSONString(operateBO));
        }
    }


    /**
     * 启动任务
     *
     * @param operateBO 参数
     * @author mengq
     */
    @Override
    public void startJob(JobTaskOperateBO operateBO) {
        //项目key
        String projectKey = this.getProjectKeyKey();
        reentrantLock.lock();
        try {
            //根据任务ID查询任务
            ScheduledQuartzJobInfo jobInfo = this.getJobByProjectKeyAndId(projectKey, operateBO.getId());

            //是否已启动校验
            if (ScheduledJobStatusEnum.ON.getValue().equals(jobInfo.getJobStatus()) &&
                    QuartzSchedulerUtil.isStart(jobInfo)) {
                throw new JobAdminExceptionJobAdmin(SysExceptionEnum.JOB_IS_RUN);
            }

            //时间表达式校验
            if (!CronExpression.isValidExpression(jobInfo.getCronExpression())) {
                throw new JobAdminExceptionJobAdmin(SysExceptionEnum.PARAM_CORN_ILLEGAL);
            }

            //任务类方法存在校验
            QuartzSchedulerUtil.checkBeanAndMethodExists(jobInfo.getJobClass(), jobInfo.getJobMethod(), jobInfo.getJobArguments());

            //启动任务
            QuartzSchedulerUtil.enable(jobInfo);

            //更新任务状态 >已启动
            this.updateJobStatusById(projectKey, operateBO.getId(), ScheduledJobStatusEnum.ON.getValue(), operateBO);

            //异步添加操作日志
            CompletableFuture.runAsync(() -> this.addLog(operateBO.getId(), SchedulerJobLogTypeEnum.OPEN, operateBO));

            log.info("TaskJobServiceImpl >> startJob end  id:{},operate:{}", operateBO.getId(), operateBO.getOperateName());
        } finally {
            reentrantLock.unlock();
            log.debug("启动任务job , 释放锁 >> startJob >> param={}", JSON.toJSONString(operateBO));
        }
    }

    /**
     * 停止任务
     *
     * @param operateBO 参数
     * @author mengq
     */
    @Override
    public void stopJob(JobTaskOperateBO operateBO) {
        //项目key
        String projectKey = this.getProjectKeyKey();
        reentrantLock.lock();
        try {
            //根据任务ID查询任务
            ScheduledQuartzJobInfo jobInfo = this.getJobByProjectKeyAndId(projectKey, operateBO.getId());

            //是否已停止校验
            if (ScheduledJobStatusEnum.OFF.getValue().equals(jobInfo.getJobStatus())) {
                throw new JobAdminExceptionJobAdmin(SysExceptionEnum.JOB_IS_STOP);
            }

            //停止任务
            QuartzSchedulerUtil.disable(jobInfo);

            //更新任务状态 >已停止
            this.updateJobStatusById(projectKey, operateBO.getId(), ScheduledJobStatusEnum.OFF.getValue(), operateBO);

            //异步添加操作日志
            CompletableFuture.runAsync(() -> this.addLog(operateBO.getId(), SchedulerJobLogTypeEnum.CLOSE, operateBO));

            log.info("TaskJobServiceImpl >> stopJob end  id:{},operate:{}", operateBO.getId(), operateBO.getOperateName());
        } finally {
            reentrantLock.unlock();
            log.debug("停止任务job , 释放锁 >> stopJob >> param={}", JSON.toJSONString(operateBO));
        }
    }

    /**
     * 任务新增前置校验
     *
     * @param projectKey 项目key
     * @param jobInfo    任务对象
     */
    private void checkBeforeAdd(String projectKey, ScheduledQuartzJobInfo jobInfo) {
        //基本参数校验
        if (this.checkParamAfterSaveOrUpdate(jobInfo)) {
            throw new JobAdminExceptionJobAdmin(SysExceptionEnum.PARAM_ILLEGAL);
        }
        //时间表达式校验
        if (!CronExpression.isValidExpression(jobInfo.getCronExpression())) {
            throw new JobAdminExceptionJobAdmin(SysExceptionEnum.PARAM_CORN_ILLEGAL);
        }
        //判重(任务组和任务名称相同的)
        int count = scheduledQuartzJobMapper.countByProjectGroupAndMethod(projectKey, jobInfo.getJobGroup(), jobInfo.getJobClass(), jobInfo.getJobMethod());
        if (count > 0) {
            throw new JobAdminExceptionJobAdmin(SysExceptionEnum.TASK_GROUP_THE_SAME_EXISTS, jobInfo.getJobGroup(), jobInfo.getJobClass(), jobInfo.getJobMethod());
        }
    }

    /**
     * 任务修改前-基础参数置校验
     *
     * @param jobInfo  参数
     * @param updateBO 参数
     */
    private void checkBaseBeforeUpdate(ScheduledQuartzJobInfo jobInfo, JobTaskUpdateBO updateBO) {
        //基本参数校验
        if (this.checkParamAfterSaveOrUpdate(jobInfo)) {
            throw new JobAdminExceptionJobAdmin(SysExceptionEnum.PARAM_ILLEGAL);
        }
        //时间表达式校验
        if (!CronExpression.isValidExpression(updateBO.getCronExpression())) {
            throw new JobAdminExceptionJobAdmin(SysExceptionEnum.PARAM_CORN_ILLEGAL);
        }
    }

    /**
     * 任务修改前-重复与是否变更校验
     *
     * @param jobInfo  参数
     * @param updateBO 参数
     */
    private void checkBeforeUpdate(String projectKey, ScheduledQuartzJobInfo jobInfo, ScheduledQuartzJobInfo oldJob, JobTaskUpdateBO updateBO) {
        //比较前后是否变化
        if (this.getJobInfoStr(jobInfo).equals(this.getJobInfoStr(oldJob))) {
            //您未修改任何信息，无需保存
            throw new JobAdminExceptionJobAdmin(SysExceptionEnum.SAVE_NOT_NEED_NOT_UPDATE);
        }
        //判重(任务组下任务类与方法名称相同的)
        int count = scheduledQuartzJobMapper.countByProjectGroupAndMethodExclude(projectKey, jobInfo.getJobGroup(),
                jobInfo.getJobClass(), jobInfo.getJobMethod(), updateBO.getId());
        if (count > 0) {
            throw new JobAdminExceptionJobAdmin(SysExceptionEnum.TASK_GROUP_THE_SAME_EXISTS, jobInfo.getJobGroup(), jobInfo.getJobClass(), jobInfo.getJobMethod());
        }
    }

    /**
     * 更新任务-记录操作日志
     *
     * @param jobId      "
     * @param oldJobInfo "
     * @param operateBO  "
     */
    private void addLogAfterUpdate(Integer jobId, ScheduledQuartzJobInfo oldJobInfo, JobBaseOperateBO operateBO) {
        try {
            ScheduledQuartzJobInfo newJobInfo = scheduledQuartzJobMapper.getJobById(jobId);
            if (null == newJobInfo) {
                return;
            }

            Map<String, Object> allFieldValues = CompareObjectUtil.getAllFieldValues(oldJobInfo, newJobInfo, ScheduledQuartzJobInfo.class);
            //记录操作日志
            this.doAddLog(jobId, SchedulerJobLogTypeEnum.UPDATE, operateBO, JSON.toJSONString(allFieldValues));
        } catch (Exception e) {
            log.error("TaskJobServiceImpl >> aync addLog  add log exception", e);
        }
    }

    /**
     * 添加日志
     *
     * @param jobId       "
     * @param logTypeEnum "
     * @param operateBO   "
     */
    private void addLog(Integer jobId, SchedulerJobLogTypeEnum logTypeEnum, JobBaseOperateBO operateBO) {
        try {
            ScheduledQuartzJobInfo scheduledQuartzJobInfo = scheduledQuartzJobMapper.getJobById(jobId);
            if (null == scheduledQuartzJobInfo) {
                return;
            }
            this.doAddLog(jobId, logTypeEnum, operateBO, JSON.toJSONString(scheduledQuartzJobInfo));
        } catch (Exception e) {
            log.error("TaskJobServiceImpl >> aync addLog add log exception", e);
        }
    }

    /**
     * 添加日志
     *
     * @param jobId       "
     * @param logTypeEnum "
     * @param operateBO   "
     * @param content     "
     */
    private void doAddLog(Integer jobId, SchedulerJobLogTypeEnum logTypeEnum, JobBaseOperateBO operateBO, String content) {
        String projectKey = this.getProjectKeyKey();
        ScheduledQuartzJobLogInfo jobLog = new ScheduledQuartzJobLogInfo();
        jobLog.setProjectKey(projectKey);
        jobLog.setJobId(jobId);
        jobLog.setLogType(logTypeEnum.getType());
        jobLog.setLogDesc(logTypeEnum.getDesc());
        jobLog.setContent(content == null ? "" : content);
        jobLog.setOperateId(operateBO.getOperateBy());
        jobLog.setOperateName(operateBO.getOperateName());
        String ipAddress = operateBO.getClientIp();
        if (!IpAddressUtil.LOCAL_IP.equals(ipAddress)) {
            String addressByIp = IpAddressUtil.getAddressByIp(ipAddress);
            if (JobAdminStringUtils.isNotBlank(addressByIp)) {
                ipAddress = ipAddress + IpAddressUtil.SEPARATE + addressByIp;
            }
        }
        jobLog.setIpAddress(ipAddress);
        String remarks = operateBO.getBrowserName();
        if (JobAdminStringUtils.isNotBlank(operateBO.getOs())) {
            remarks = remarks + IpAddressUtil.SEPARATE + operateBO.getOs();
        }
        jobLog.setRemarks(remarks);
        scheduledQuartzJobLogMapper.addLog(jobLog);
    }


    /**
     * 根据ID修改任务
     */
    private void updateJobStatusById(String projectKey, Integer id, Integer jobStatus, JobBaseOperateBO operateBO) {
        ScheduledQuartzJobInfo updateJobInfo = new ScheduledQuartzJobInfo();
        updateJobInfo.setId(id);
        updateJobInfo.setProjectKey(projectKey);
        updateJobInfo.setJobStatus(jobStatus);
        updateJobInfo.setUpdateBy(operateBO.getOperateBy());
        updateJobInfo.setUpdateName(operateBO.getOperateName());
        //更新任务状态
        scheduledQuartzJobMapper.updateByProjectAndId(updateJobInfo);
    }

    /**
     * 根据项目key与任务ID查询
     */
    private ScheduledQuartzJobInfo getJobByProjectKeyAndId(String projectKey, Integer id) {
        if (null == id) {
            throw new JobAdminExceptionJobAdmin(SysExceptionEnum.TASK_NOT_EXISTS);
        }
        ScheduledQuartzJobInfo jobInfo = scheduledQuartzJobMapper.getJobByProjectAndId(id, projectKey);
        if (null == jobInfo) {
            throw new JobAdminExceptionJobAdmin(SysExceptionEnum.TASK_NOT_EXISTS);
        }
        return jobInfo;
    }

    /**
     * 获取任务key
     */
    private String getProjectKeyKey() {
        String projectKey = basicJobConfig.getProjectKey();
        if (JobAdminStringUtils.isBlank(projectKey)) {
            throw new JobAdminExceptionJobAdmin(SysExceptionEnum.PROJECT_NOT_EXISTS);
        }
        return projectKey;
    }

    /**
     * 构建任务参数
     *
     * @param projectKey
     * @param jobSaveBO
     */
    private ScheduledQuartzJobInfo buildScheduledQuartzJobInfo(String projectKey, JobTaskSaveBO jobSaveBO) {
        ScheduledQuartzJobInfo scheduledQuartzJobInfo = new ScheduledQuartzJobInfo();
        scheduledQuartzJobInfo.setProjectKey(projectKey);
        scheduledQuartzJobInfo.setJobClass(jobSaveBO.getJobClass());
        scheduledQuartzJobInfo.setJobMethod(jobSaveBO.getJobMethod());
        scheduledQuartzJobInfo.setJobGroup(jobSaveBO.getJobGroup());
        scheduledQuartzJobInfo.setJobName(jobSaveBO.getJobName());
        scheduledQuartzJobInfo.setCronExpression(jobSaveBO.getCronExpression());
        scheduledQuartzJobInfo.setCreateBy(jobSaveBO.getOperateBy());
        scheduledQuartzJobInfo.setCreateName(jobSaveBO.getOperateName());
        scheduledQuartzJobInfo.setUpdateBy(jobSaveBO.getOperateBy());
        scheduledQuartzJobInfo.setUpdateName(jobSaveBO.getOperateName());
        scheduledQuartzJobInfo.setDescription(JobAdminStringUtils.isBlank(jobSaveBO.getDescription()) ? "" : jobSaveBO.getDescription());
        scheduledQuartzJobInfo.setJobArguments(JobAdminStringUtils.isBlank(jobSaveBO.getJobArguments()) ? "" : jobSaveBO.getJobArguments());
        return scheduledQuartzJobInfo;
    }

    /**
     * 检查参数是否合法
     */
    boolean checkParamAfterSaveOrUpdate(ScheduledQuartzJobInfo scheduledQuartzJobInfo) {
        if (JobAdminStringUtils.isBlank(scheduledQuartzJobInfo.getCronExpression())) {
            return true;
        }
        if (JobAdminStringUtils.isBlank(scheduledQuartzJobInfo.getJobClass())) {
            return true;
        }
        if (JobAdminStringUtils.isBlank(scheduledQuartzJobInfo.getJobMethod())) {
            return true;
        }
        if (JobAdminStringUtils.isBlank(scheduledQuartzJobInfo.getJobGroup())) {
            return true;
        }

        return JobAdminStringUtils.isBlank(scheduledQuartzJobInfo.getJobName());
    }

    private String getJobInfoStr(ScheduledQuartzJobInfo jobInfo) {
        return jobInfo.getJobMethod() + jobInfo.getJobArguments() + jobInfo.getJobGroup() + jobInfo.getJobName() + jobInfo.getJobClass() + jobInfo.getCronExpression() + jobInfo.getDescription();
    }
}
