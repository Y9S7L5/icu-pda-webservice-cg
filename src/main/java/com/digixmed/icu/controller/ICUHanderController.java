package com.digixmed.icu.controller;

import com.digixmed.icu.service.OrderService;
import com.digixmed.icu.viform.IcuRecordView;
import io.swagger.annotations.ApiOperation;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;


@RequestMapping({"/ICU"})
@RestController
public class ICUHanderController {
    @Autowired
    private OrderService orderService;

    @GetMapping({"/test"})
    @ApiOperation("测试获取PDA视图数据")
    public String test() {
        List<IcuRecordView> recordsAll = orderService.getRecordsAll();
        return recordsAll.toString();
    }

    @GetMapping({"/records/recent"})
    @ApiOperation("测试获取最近N小时的视图数据（record_time >= 当前时间-N小时），默认24小时")
    public List<IcuRecordView> recentRecords(@RequestParam(name = "hours", defaultValue = "24") Integer hours) {
        int h = (hours == null || hours <= 0) ? 24 : hours;
        return orderService.getRecordsRecentHours(h);
    }

    @GetMapping({"/records/all"})
    @ApiOperation("测试获取视图全量数据")
    public List<IcuRecordView> allRecords() {
        return orderService.getRecordsAll();
    }

    @GetMapping({"/synOrderExecute"})
    @ApiOperation("同步医嘱执行记录（最近N小时）到DataCenter.VI_ICU_HSZ_YYZXJL：持续用药除摆药/配药外全事件同步，单次用药组内仅留最早执行行；按 exeId upsert")
    public String synOrderExecute() {
        return formatSynResult(this.orderService.synOrderExecute());
    }

    @GetMapping({"/synOrderExecute/all"})
    @ApiOperation("全量同步医嘱执行记录（不限时间窗口）到DataCenter.VI_ICU_HSZ_YYZXJL：持续用药除摆药/配药外全事件同步，单次用药组内仅留最早执行行；按 exeId upsert，幂等可重复执行")
    public String synOrderExecuteAll() {
        return formatSynResult(this.orderService.synOrderExecuteAll());
    }

    private String formatSynResult(Map<String, Integer> result) {
        return "同步完成：扫描 " + result.get("scan") + " 条，状态过滤后 " + result.get("filtered")
                + " 条，去重剔除 " + result.get("dedup") + " 条（整组跳过 " + result.get("skippedGroups")
                + " 组），快推 " + result.get("fastPush") + " 条，新增 " + result.get("insert") + " 条，更新 " + result.get("update")
                + " 条（按 exeId upsert：没有新增，有则修改）";
    }

}
