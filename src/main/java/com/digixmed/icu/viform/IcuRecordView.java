package com.digixmed.icu.viform;

import javax.persistence.*;
import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import java.util.Date;
@Data
@Entity
@Table(name = "vi_icu_hsz_yzzxjl", schema = "np_nis_cqchonggang") // 如果 yaml 里没配 currentSchema，这里必须写 schema
public class IcuRecordView {

    @Id // 必须有主键，如果是视图没有主键，选一个唯一字段或者组合字段
    @Column(name = "exeid")
    private String exeid;

    // 视图 2026-10 变更后已无 orderid/amount/amountunit 列，删除对应映射（orderID 业务上取 groupid）
    @Column(name = "barcode")
    private String barcode;

    @Column(name = "plan_time")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    private Date planTime;

    /**
     * 对应数据库中的 record_time (Date 类型)
     */
    @Column(name = "record_time")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    private Date recordTime;

    @Column(name = "status")
    private String status;

    @Column(name = "speed")
    private String speed;

    @Column(name = "note")
    private String note;

    @Column(name = "groupid")
    private String groupId;

    @Column(name = "nurseusername")
    private String nurseUsername;

    @Column(name = "nurseuserid")
    private String nurseUserId;

    @Column(name = "zyh")
    private String zyh;

    /** HIS 视图新增列：给药途径编码（供药码），用于匹配 configDrugMethod */
    @Column(name = "supply_code")
    private String supplyCode;

    /** HIS 视图新增列：给药途径名称 */
    @Column(name = "supply_name")
    private String supplyName;

    /** HIS 视图新增列：快推量。非单次用药记录该值非空时，额外生成一条 status=快推 的执行记录 */
    @Column(name = "fast_push_amount")
    private String fastPushAmount;

    // Getters and Setters ...
}