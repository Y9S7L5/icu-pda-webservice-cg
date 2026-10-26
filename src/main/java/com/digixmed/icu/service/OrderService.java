package com.digixmed.icu.service;

import cn.hutool.core.collection.CollectionUtil;
import cn.hutool.core.date.DateUtil;
import com.digixmed.icu.dao.IcuRecordRepository;
import com.digixmed.icu.dao.MongoDao;
import com.digixmed.icu.viform.IcuRecordView;
import com.digixmed.icu.viform.Patient;
import lombok.extern.slf4j.Slf4j;
import org.bson.Document;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
@Slf4j
public class OrderService  {

    /** 视图中需要排除的状态：摆药、配药 */
    private static final Set<String> EXCLUDE_STATUS = new HashSet<>(Arrays.asList("摆药", "配药"));

    /** 视图中需要排除的给药途径名称（supply_name）：检验（非医嘱执行数据，不同步到 VI 表） */
    private static final Set<String> EXCLUDE_SUPPLY_NAMES = new HashSet<>(Arrays.asList("检验"));

    /** 执行事件状态名：单次用药（isOnce）的组只保留状态为"执行"的最早一条 */
    private static final String EXEC_STATUS = "执行";

    /** 快推记录状态名：非单次用药记录 fast_push_amount 非空时，额外生成一条 status=快推 的执行记录 */
    private static final String FAST_PUSH_STATUS = "快推";

    /** 写入 VI_ICU_HSZ_YYZXJL 时的 _class，需与对端系统实体一致 */
    private static final String ORDER_EXECUTE_CLASS = "com.digixmed.web.internal.data.entity.OrderExecute";

    @Autowired
    MongoDao mongoDao;

    @Autowired
    private IcuRecordRepository repository;

    /** 增量同步回看窗口（小时），兜住窗口内的护士补录 */
    @Value("${digixmed.order-execute-lookback-hours:24}")
    private int orderExecuteLookbackHours;

    public void syPDA(){
        List<Patient> admittedPatient = this.mongoDao.findAdmittedPatient(List.of("admitted", "wait_admitted", "wait_discharged"));
        if(CollectionUtil.isEmpty(admittedPatient))return;
        Date date = new Date();

        for (Patient patient : admittedPatient) {

        }

    }

    /**
     * 同步医嘱执行记录：只取 record_time 在最近 N 小时（order-execute-lookback-hours，默认24）内的记录
     * 同步范围按给药方式区分：持续用药除摆药/配药外全部事件行都同步；单次用药组内只留最早执行行
     * 按 exeId upsert：库里没有则新增，有则修改，可重复执行
     */
    public Map<String, Integer> synOrderExecute() {
        Date start = DateUtil.offsetHour(new Date(), -this.orderExecuteLookbackHours);
        // 视图是事件流水（同一 exeid 有摆药/配药/执行/结束等多行，HIS 侧将改为每事件唯一 exeid），
        // 取窗口内全部状态行，由 doSynOrderExecute 按给药方式决定保留范围
        List<IcuRecordView> records = repository.findByRecordTimeGreaterThanEqual(start);
        log.info("医嘱执行记录同步：回看 {} 小时，窗口 {} 至今", this.orderExecuteLookbackHours, start);
        return doSynOrderExecute(records);
    }

    /**
     * 全量同步医嘱执行记录：不限时间窗口，取视图全部状态行
     * 过滤/去重/按 exeId upsert 与增量同步一致，幂等可重复执行
     */
    public Map<String, Integer> synOrderExecuteAll() {
        log.info("医嘱执行记录全量同步：视图全量（全部状态）");
        return doSynOrderExecute(repository.findAll());
    }

    /**
     * 把金仓视图 vi_icu_hsz_yzzxjl 的执行记录同步到 DataCenter.VI_ICU_HSZ_YYZXJL
     * 注意：视图是按状态的事件流水（同一 exeid 有 摆药/配药/执行/结束 等多行，各自有 record_time；
     *       HIS 侧将改为每事件唯一 exeid），record_time 即该事件的发生时刻
     * 同步范围按给药方式区分：
     *       持续用药（configDrugMethod.isOnce != true 或匹配不到配置）：除摆药/配药外全部事件行
     *       都同步（执行、结束等每个事件一条文档）
     *       单次用药（isOnce = true）：按 barcode 分组（PDA 执行的一组 barcode 一致），组内
     *          supply_code 匹配 configDrugMethod 的 code（、分隔多值），只保留 status=执行
     *          的最早一条，无执行行整组跳过（跳过组打日志便于排查）
     * 字段映射：exeId=exeid、orderID=groupid、barCode=barcode、planTime=record_time、
     *          exeTime=record_time、status=status、speed=speed(#前数值)、nurseTrueName/nurseUserName=nurseusername、
     *          supplyCode=supply_code、supplyName=supply_name
     * 快推记录：非单次用药（持续用药或匹配不到配置）的行 fast_push_amount 非空（非 null 且非空串）时，
     *          额外生成一条 status=快推、inAmount=fast_push_amount、exeId=源 exeId 尾部数字 +1 的文档，
     *          其余字段与源记录一致
     * 排除 status 为 摆药/配药 的记录，以及 supplyName 为 检验 的记录（非医嘱执行数据）
     * 幂等：按 exeId upsert，视图里已同步过的老数据只会被更新，不会重复插入
     *
     * @return scan=视图扫描条数, filtered=状态过滤后条数, dedup=去重剔除条数,
     *         skippedGroups=整组跳过组数, fastPush=快推生成条数, insert=新增条数, update=更新条数
     */
    private Map<String, Integer> doSynOrderExecute(List<IcuRecordView> records) {
        Map<String, Integer> result = new HashMap<>();
        result.put("insert", 0);
        result.put("update", 0);
        result.put("scan", records == null ? 0 : records.size());
        result.put("filtered", 0);
        result.put("dedup", 0);
        result.put("skippedGroups", 0);
        result.put("fastPush", 0);
        if (CollectionUtil.isEmpty(records)) {
            log.info("医嘱执行记录同步：视图无数据");
            return result;
        }
        // 步骤0：视图可能因关联产生 exeid 重复行（HIS 修好前同一 exeid 还会有多事件行）。
        // Hibernate 同一会话内同一 exeid 只保留一个实体实例，重复行会折叠成同一引用，
        // 导致后面按引用去重失效、同一 exeId 被重复 upsert——先按 exeid 折叠为唯一行。
        // HIS 把 exeid 改为每事件唯一后，此步骤只兜视图关联放大，不会误折叠多事件
        List<IcuRecordView> distinctRecords = new ArrayList<>();
        Map<String, IcuRecordView> byExeId = new LinkedHashMap<>();
        int dupRows = 0;
        for (IcuRecordView record : records) {
            String exeId = record.getExeid() == null ? null : record.getExeid().trim();
            if (exeId == null || exeId.isEmpty()) {
                distinctRecords.add(record);
                continue;
            }
            if (byExeId.putIfAbsent(exeId, record) == null) {
                distinctRecords.add(record);
            } else {
                dupRows++;
            }
        }
        if (dupRows > 0) {
            log.warn("医嘱执行记录同步：视图存在 exeid 重复行 {} 条（同一 exeid 多行，疑似视图关联放大），已折叠为唯一行", dupRows);
        }
        // 步骤1：过滤（排除摆药/配药 及 supplyName=检验，并统计状态分布核对数据构成）
        List<IcuRecordView> postFilter = new ArrayList<>();
        Map<String, Integer> statusCount = new HashMap<>();
        int excludeStatus = 0;
        int excludeLab = 0;
        for (IcuRecordView record : distinctRecords) {
            String status = record.getStatus() == null ? "" : record.getStatus().trim();
            statusCount.merge(status.isEmpty() ? "(空)" : status, 1, Integer::sum);
            if (!status.isEmpty() && EXCLUDE_STATUS.contains(status)) {
                excludeStatus++;
                continue;
            }
            String supplyName = record.getSupplyName() == null ? null : record.getSupplyName().trim();
            if (supplyName != null && EXCLUDE_SUPPLY_NAMES.contains(supplyName)) {
                excludeLab++;
                continue;
            }
            postFilter.add(record);
        }
        // 按排除原因分组统计，便于核对视图数据构成（status 分布含被排除的行，基于 exeid 去重后的行数）
        log.info("医嘱执行记录同步：视图共 {} 条（exeid 去重后 {} 条），排除 status=摆药/配药 {} 条、supplyName=检验 {} 条，状态过滤后 {} 条；状态分布：{}",
                records.size(), distinctRecords.size(), excludeStatus, excludeLab, postFilter.size(), statusCount);
        result.put("filtered", postFilter.size());
        if (CollectionUtil.isEmpty(postFilter)) {
            log.info("医嘱执行记录同步：过滤后无数据，视图共 {} 条", records.size());
            return result;
        }
        // 步骤2：本次同步加载一次给药方法配置；失败则降级为本轮不去重，不阻断同步
        List<Document> drugMethods;
        try {
            drugMethods = this.mongoDao.findValidDrugMethods();
        } catch (Exception e) {
            log.error("configDrugMethod 加载失败，本次不做单次用药去重：{}", e.getMessage());
            drugMethods = new ArrayList<>();
        }
        // 步骤3：按 barcode 分组做表单单次用药去重；同时收集非单次用药的保留行（用于生成快推记录）
        Set<IcuRecordView> nonOnce = Collections.newSetFromMap(new IdentityHashMap<>());
        List<IcuRecordView> kept = dedupOnceGroups(postFilter, drugMethods, result, nonOnce);
        result.put("dedup", postFilter.size() - kept.size());
        log.info("医嘱执行记录同步：去重后保留 {} 条（剔除 {} 条）", kept.size(), result.get("dedup"));
        if (CollectionUtil.isEmpty(kept)) {
            log.info("医嘱执行记录同步：去重后无数据，状态过滤后 {} 条", postFilter.size());
            return result;
        }
        // 步骤4：构建文档（原字段映射不变；非单次用药 fast_push_amount 非空的额外生成快推记录）
        List<Document> docList = buildExecuteDocs(kept, nonOnce, result);
        // 步骤5：按 exeId upsert（不变）
        Map<String, Integer> count = this.mongoDao.updateOrSaveOrderExecDocs(docList);
        result.putAll(count);
        log.info("医嘱执行记录同步完成：视图 {} 条，状态过滤后 {} 条，去重剔除 {} 条（整组跳过 {} 组），快推 {} 条，新增 {} 条，更新 {} 条",
                records.size(), postFilter.size(), result.get("dedup"), result.get("skippedGroups"),
                result.get("fastPush"), count.get("insert"), count.get("update"));
        return result;
    }

    /**
     * 按 barcode 分组做表单单次用药（isOnce）去重：
     * - 供药码为空 / 匹配不到配置 / 配置 isOnce != true 的组（持续用药）：整组原样保留，
     *   执行、结束等全部事件行都同步
     * - isOnce 组（单次用药）：只保留 status=执行 的行；多条执行行保留 record_time 最早的一条并告警；
     *   整组无执行行则整组跳过并告警（方便排查）
     * - barcode 为空的行不参与分组（避免所有空值被并进同一个"空组"误删无关医嘱），按原样逐条保留并聚合告警
     *
     * @param result      出参：累加 skippedGroups（整组跳过的组数）
     * @return 保留参与同步的记录（保持原有输入顺序）
     */
    List<IcuRecordView> dedupOnceGroups(List<IcuRecordView> postFilter,
                                        List<Document> drugMethods,
                                        Map<String, Integer> result) {
        return dedupOnceGroups(postFilter, drugMethods, result, null);
    }

    /**
     * 按 barcode 分组做表单单次用药（isOnce）去重，见 {@link #dedupOnceGroups(List, List, Map)}：
     *
     * @param nonOnce 出参（可为 null）：判定为非单次用药（持续用药或匹配不到配置）且被保留的记录，
     *                供后续按 fast_push_amount 生成快推记录使用（按引用识别，与 kept 中的行同实例）
     */
    List<IcuRecordView> dedupOnceGroups(List<IcuRecordView> postFilter,
                                        List<Document> drugMethods,
                                        Map<String, Integer> result,
                                        Set<IcuRecordView> nonOnce) {
        // 被剔除的行集合（按引用识别），最后一遍按原顺序收集保留行
        Set<IcuRecordView> dropped = Collections.newSetFromMap(new IdentityHashMap<>());
        Map<String, List<IcuRecordView>> groups = new LinkedHashMap<>();
        int noBarcode = 0;
        for (IcuRecordView record : postFilter) {
            String barcode = record.getBarcode() == null ? null : record.getBarcode().trim();
            if (barcode == null || barcode.isEmpty()) {
                // 空 barcode 不分组，按原样逐条同步；是否单次用药按自身供药码判断
                noBarcode++;
                if (nonOnce != null && !isOnceRecord(record, drugMethods)) {
                    nonOnce.add(record);
                }
                continue;
            }
            groups.computeIfAbsent(barcode, k -> new ArrayList<>()).add(record);
        }
        if (noBarcode > 0) {
            log.warn("医嘱执行去重：{} 条 barcode 为空的记录跳过去重逻辑，按原样逐条同步", noBarcode);
        }
        for (Map.Entry<String, List<IcuRecordView>> entry : groups.entrySet()) {
            String barcode = entry.getKey();
            List<IcuRecordView> group = entry.getValue();
            // 组内 PDA 执行途径一致，取第一个非空 supplyCode
            String supplyCode = null;
            for (IcuRecordView record : group) {
                String code = record.getSupplyCode();
                if (code != null && !code.trim().isEmpty()) {
                    supplyCode = code.trim();
                    break;
                }
            }
            Document config = findDrugMethod(supplyCode, drugMethods);
            if (config == null || !Boolean.TRUE.equals(config.getBoolean("isOnce"))) {
                // 非单次用药或匹配不到配置：整组原样保留，并记录为非单次用药（生成快推记录的候选）
                if (nonOnce != null) {
                    nonOnce.addAll(group);
                }
                continue;
            }
            // 单次用药：只保留 status=执行 的最早一条
            List<IcuRecordView> execRows = new ArrayList<>();
            for (IcuRecordView record : group) {
                String status = record.getStatus() == null ? null : record.getStatus().trim();
                if (EXEC_STATUS.equals(status)) {
                    execRows.add(record);
                }
            }
            if (execRows.isEmpty()) {
                result.merge("skippedGroups", 1, Integer::sum);
                dropped.addAll(group);
                log.warn("医嘱执行去重：[整组跳过] barcode={}, groupid={}, supplyCode={}, 共 {} 行，原因=无执行行整组跳过",
                        barcode, firstGroupId(group), supplyCode, group.size());
                continue;
            }
            IcuRecordView chosen = execRows.stream()
                    .min(Comparator.comparing(IcuRecordView::getRecordTime,
                            Comparator.nullsFirst(Comparator.naturalOrder())))
                    .get();
            if (execRows.size() > 1) {
                log.warn("医嘱执行去重：[执行行多条仅保留最早] barcode={}, groupid={}, 执行行 {} 条，保留 exeId={}（recordTime={}）",
                        barcode, firstGroupId(group), execRows.size(), chosen.getExeid(), chosen.getRecordTime());
            }
            int removed = 0;
            for (IcuRecordView record : group) {
                if (record != chosen) {
                    dropped.add(record);
                    removed++;
                }
            }
            log.info("医嘱执行去重：[保留] barcode={}, exeId={}，本组剔除 {} 行",
                    barcode, chosen.getExeid(), removed);
        }
        List<IcuRecordView> kept = new ArrayList<>();
        for (IcuRecordView record : postFilter) {
            if (!dropped.contains(record)) {
                kept.add(record);
            }
        }
        return kept;
    }

    /**
     * 取组内第一个非空 groupid，用于日志展示
     */
    private String firstGroupId(List<IcuRecordView> group) {
        for (IcuRecordView record : group) {
            if (!StringUtils.isEmpty(record.getGroupId())) {
                return record.getGroupId();
            }
        }
        return null;
    }

    /**
     * 单条记录是否单次用药：按自身的 supply_code 匹配 configDrugMethod，
     * 匹配不到配置或配置 isOnce != true 均视为非单次用药（与分组判定规则一致）
     */
    static boolean isOnceRecord(IcuRecordView record, List<Document> drugMethods) {
        Document config = findDrugMethod(record.getSupplyCode(), drugMethods);
        return config != null && Boolean.TRUE.equals(config.getBoolean("isOnce"));
    }

    /**
     * 构建待写入文档列表：
     * - 每条保留记录生成一条常规执行文档（字段映射与原逻辑一致）
     * - 非单次用药（持续用药或匹配不到配置）的记录，fast_push_amount 非空（null 或空串视为无）时
     *   额外生成一条快推文档：exeId = 源 exeId 尾部数字整体 +1，status=快推，inAmount=fast_push_amount，
     *   barCode/exeTime/nurseTrueName/nurseUserName/orderID/planTime/speed/supplyCode/supplyName 与源记录一致
     * - exeId 无尾部数字、或 +1 后与本批已有 exeId（源记录/已生成的快推记录）冲突时跳过该条并告警，
     *   避免 upsert 时覆盖真实事件文档
     *
     * @param nonOnce 非单次用药的保留记录集合（按引用识别，可为 null）
     * @param result  出参：累加 fastPush（实际生成的快推条数）
     */
    List<Document> buildExecuteDocs(List<IcuRecordView> kept,
                                    Set<IcuRecordView> nonOnce,
                                    Map<String, Integer> result) {
        List<Document> docList = new ArrayList<>();
        // 本批已占用的 exeId（源记录 + 已生成的快推记录），用于冲突检测
        Set<String> usedExeIds = new HashSet<>();
        for (IcuRecordView record : kept) {
            String exeId = record.getExeid() == null ? null : record.getExeid().trim();
            if (exeId != null && !exeId.isEmpty()) {
                usedExeIds.add(exeId);
            }
            docList.add(buildExecuteDoc(record));
        }
        int fastPushCount = 0;
        for (IcuRecordView record : kept) {
            if (nonOnce == null || !nonOnce.contains(record)) {
                continue;
            }
            String fastPushAmount = record.getFastPushAmount() == null ? null : record.getFastPushAmount().trim();
            if (fastPushAmount == null || fastPushAmount.isEmpty()) {
                continue;
            }
            String fastExeId = fastPushExeId(record.getExeid());
            if (fastExeId == null) {
                log.warn("快推记录生成跳过：exeId 无末尾数字无法 +1，exeId={}, fastPushAmount={}",
                        record.getExeid(), fastPushAmount);
                continue;
            }
            if (!usedExeIds.add(fastExeId)) {
                log.warn("快推记录生成跳过：exeId+1 与本批已有 exeId 冲突，源 exeId={}, 快推 exeId={}, fastPushAmount={}",
                        record.getExeid(), fastExeId, fastPushAmount);
                continue;
            }
            Document fastDoc = buildExecuteDoc(record);
            fastDoc.put("exeId", fastExeId);
            fastDoc.put("status", FAST_PUSH_STATUS);
            fastDoc.put("inAmount", fastPushAmount);
            docList.add(fastDoc);
            fastPushCount++;
            log.info("快推记录生成：源 exeId={}, 快推 exeId={}, inAmount={}",
                    record.getExeid(), fastExeId, fastPushAmount);
        }
        if (fastPushCount > 0) {
            result.merge("fastPush", fastPushCount, Integer::sum);
        }
        return docList;
    }

    /**
     * 构建单条执行记录文档（字段映射：exeId=exeid、orderID=groupid、barCode=barcode、
     * planTime/exeTime=record_time、status、speed(#前数值)、nurseTrueName/nurseUserName=nurseusername、
     * supplyCode/supplyName）
     */
    private Document buildExecuteDoc(IcuRecordView record) {
        Document doc = new Document();
        doc.put("_class", ORDER_EXECUTE_CLASS);
        doc.put("exeId", record.getExeid());
        // orderID 对应视图的 groupid
        doc.put("orderID", record.getGroupId());
        doc.put("barCode", record.getBarcode());
        // 计划时间取视图的 record_time（不再用 plan_time）
        doc.put("planTime", record.getRecordTime());
        doc.put("exeTime", record.getRecordTime());
        doc.put("status", record.getStatus() == null ? null : record.getStatus().trim());
        doc.put("speed", parseSpeed(record.getSpeed()));
        doc.put("nurseTrueName", record.getNurseUsername());
        // 新增字段：护士用户名
        doc.put("nurseUserName", record.getNurseUsername());
        // 新增字段：给药途径编码/名称（视图 supply_code / supply_name）
        doc.put("supplyCode", record.getSupplyCode());
        doc.put("supplyName", record.getSupplyName());
        return doc;
    }

    /**
     * 快推记录的 exeId：取源 exeId 末尾连续数字段整体 +1（如 …142926 → …142927）。
     * 进位变长时直接使用新长度；去前导零变短时左侧补零保持原数字段长度。
     * exeId 为空或无末尾数字时返回 null（调用方跳过生成并告警）
     */
    static String fastPushExeId(String exeId) {
        if (exeId == null || exeId.trim().isEmpty()) {
            return null;
        }
        String trimmed = exeId.trim();
        int start = trimmed.length();
        while (start > 0 && Character.isDigit(trimmed.charAt(start - 1))) {
            start--;
        }
        if (start == trimmed.length()) {
            return null;
        }
        String digits = trimmed.substring(start);
        String incremented = new BigInteger(digits).add(BigInteger.ONE).toString();
        while (incremented.length() < digits.length()) {
            incremented = "0" + incremented;
        }
        return trimmed.substring(0, start) + incremented;
    }

    /**
     * 按供药码匹配给药方法配置。configDrugMethod 的 code 可能是中文顿号 、 分隔的多值（如 "103、104、105"）
     */
    static Document findDrugMethod(String supplyCode, List<Document> configs) {
        if (configs == null || supplyCode == null || supplyCode.trim().isEmpty()) {
            return null;
        }
        String target = supplyCode.trim();
        for (Document config : configs) {
            if (Boolean.FALSE.equals(config.getBoolean("valid"))) {
                continue;
            }
            String code = config.getString("code");
            if (code == null) {
                continue;
            }
            if (normalizeCodes(code).contains(target)) {
                return config;
            }
        }
        return null;
    }

    /**
     * 按中文顿号 、 切割 code，去空白、去空段
     */
    static List<String> normalizeCodes(String code) {
        List<String> result = new ArrayList<>();
        if (code == null) {
            return result;
        }
        for (String item : code.split("、")) {
            String trimmed = item.trim();
            if (!trimmed.isEmpty()) {
                result.add(trimmed);
            }
        }
        return result;
    }

    /**
     * speed 形如 "16#滴/分"，按 # 切割取第一段，得到 "16"
     */
    private String parseSpeed(String speed) {
        if (speed == null) {
            return null;
        }
        String value = speed.split("#")[0].trim();
        return StringUtils.isEmpty(value) ? null : value;
    }

    public List<IcuRecordView> getRecordsByTimeRange(String zyh, Date start, Date end) {
        // 直接调用，无需写 SQL
        return repository.findByZyhAndRecordTimeBetween(zyh, start, end);
    }

    public List<IcuRecordView> getRecordsAll() {
        // 直接调用，无需写 SQL
        return repository.findAll();
    }

    /**
     * 按小时窗口查询视图：record_time >= 当前时间 - N 小时
     */
    public List<IcuRecordView> getRecordsRecentHours(int hours) {
        Date start = DateUtil.offsetHour(new Date(), -hours);
        return repository.findByRecordTimeGreaterThanEqual(start);
    }

}