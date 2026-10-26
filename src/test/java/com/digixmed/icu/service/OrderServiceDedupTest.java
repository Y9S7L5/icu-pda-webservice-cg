package com.digixmed.icu.service;

import com.digixmed.icu.viform.IcuRecordView;
import org.bson.Document;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 单次用药（isOnce）按 barcode 去重逻辑的单元测试
 * 覆盖：code 多值匹配、取最早执行行、无执行行整组跳过、
 *       非 isOnce（持续用药）组执行+结束全保留、isOnce 组执行+结束只留执行、空值组原样保留
 */
class OrderServiceDedupTest {

    private final OrderService orderService = new OrderService();

    private IcuRecordView row(String exeid, String barcode, String status, String supplyCode, Date recordTime) {
        IcuRecordView r = new IcuRecordView();
        r.setExeid(exeid);
        r.setBarcode(barcode);
        r.setStatus(status);
        r.setSupplyCode(supplyCode);
        r.setRecordTime(recordTime);
        r.setGroupId("g-" + exeid);
        return r;
    }

    /** 构造一条有效配置（code 可为 、 分隔多值） */
    private Document config(String code, boolean isOnce) {
        return new Document("code", code).append("isOnce", isOnce).append("valid", true);
    }

    /** 同一时刻不同秒的时间点，仅用于比较先后 */
    private Date t(int second) {
        Calendar c = Calendar.getInstance();
        c.set(2026, Calendar.OCTOBER, 9, 12, 0, second);
        c.set(Calendar.MILLISECOND, 0);
        return c.getTime();
    }

    private List<String> ids(List<IcuRecordView> list) {
        List<String> ids = new ArrayList<>();
        for (IcuRecordView r : list) {
            ids.add(r.getExeid());
        }
        return ids;
    }

    /* ==== normalizeCodes ==== */

    @Test
    void normalizeCodes_splitsChineseEnumerationComma() {
        assertEquals(Arrays.asList("103", "104", "105"), OrderService.normalizeCodes("103、 104、、105"));
        assertEquals(Collections.singletonList("1"), OrderService.normalizeCodes("1"));
        assertTrue(OrderService.normalizeCodes(null).isEmpty());
        assertTrue(OrderService.normalizeCodes("").isEmpty());
    }

    /* ==== findDrugMethod ==== */

    @Test
    void findDrugMethod_matchesMultiCodeAndSkipsInvalid() {
        List<Document> configs = Arrays.asList(
                config("2", false) /* 测试内后面覆盖 valid=false */,
                config("103、104、105", true));
        configs.get(0).put("valid", false);

        // 多值 code 命中
        Document hit = OrderService.findDrugMethod("104", configs);
        assertNotNull(hit);
        assertEquals(Boolean.TRUE, hit.getBoolean("isOnce"));

        // valid=false 即使命中 code 也跳过
        assertNull(OrderService.findDrugMethod("2", configs));
        // 未命中 / 空入参
        assertNull(OrderService.findDrugMethod("999", configs));
        assertNull(OrderService.findDrugMethod(null, configs));
        assertNull(OrderService.findDrugMethod(" ", configs));
        assertNull(OrderService.findDrugMethod("104", null));
    }

    /* ==== dedupOnceGroups ==== */

    @Test
    void onceGroup_singleExecKeepsOnlyThatRow() {
        List<Document> configs = Collections.singletonList(config("104", true));
        List<IcuRecordView> input = Arrays.asList(
                row("e1", "BC1", "复核", "104", t(10)),
                row("e2", "BC1", "复核", "104", t(20)),
                row("e3", "BC1", "执行", "104", t(30)));
        Map<String, Integer> result = new HashMap<>();
        List<IcuRecordView> kept = orderService.dedupOnceGroups(input, configs, result);
        assertEquals(Collections.singletonList("e3"), ids(kept));
        assertEquals(0, result.getOrDefault("skippedGroups", 0));
    }

    @Test
    void onceGroup_multipleExecKeepsEarliest() {
        List<Document> configs = Collections.singletonList(config("103、104", true));
        List<IcuRecordView> input = Arrays.asList(
                row("e1", "BC1", "执行", "104", t(50)),
                row("e2", "BC1", "执行", "104", t(10)),
                row("e3", "BC1", "复核", "104", t(60)));
        Map<String, Integer> result = new HashMap<>();
        List<IcuRecordView> kept = orderService.dedupOnceGroups(input, configs, result);
        assertEquals(Collections.singletonList("e2"), ids(kept));
        assertEquals(0, result.getOrDefault("skippedGroups", 0));
    }

    @Test
    void onceGroup_noExecSkipsWholeGroupAndCounts() {
        List<Document> configs = Collections.singletonList(config("104", true));
        List<IcuRecordView> input = Arrays.asList(
                row("e1", "BC1", "复核", "104", t(10)),
                row("e2", "BC1", "复核", "104", t(20)));
        Map<String, Integer> result = new HashMap<>();
        List<IcuRecordView> kept = orderService.dedupOnceGroups(input, configs, result);
        assertTrue(kept.isEmpty());
        assertEquals(1, result.getOrDefault("skippedGroups", 0));
    }

    @Test
    void nonOnceOrUnmatchedGroupKeepsAllRows() {
        List<Document> configs = Arrays.asList(config("1", false), config("104", true));
        List<IcuRecordView> input = Arrays.asList(
                // isOnce=false 的组：原样保留
                row("n1", "BC2", "执行", "1", t(10)),
                row("n2", "BC2", "执行", "1", t(20)),
                // 匹配不到配置的组：原样保留
                row("n3", "BC3", "执行", "999", t(10)),
                row("n4", "BC3", "执行", "999", t(20)));
        Map<String, Integer> result = new HashMap<>();
        List<IcuRecordView> kept = orderService.dedupOnceGroups(input, configs, result);
        assertEquals(Arrays.asList("n1", "n2", "n3", "n4"), ids(kept));
        assertEquals(0, result.getOrDefault("skippedGroups", 0));
    }

    @Test
    void nullBarcodeAndNullSupplyCodeRowsKeptAsIs() {
        List<Document> configs = Collections.singletonList(config("104", true));
        List<IcuRecordView> input = Arrays.asList(
                // barcode 为空：不参与分组，逐条保留
                row("b1", null, "执行", "104", t(10)),
                row("b2", "", "执行", "104", t(20)),
                // supplyCode 为空的组：匹配不到，整组保留
                row("s1", "BC4", "执行", null, t(10)),
                row("s2", "BC4", "执行", null, t(20)));
        Map<String, Integer> result = new HashMap<>();
        List<IcuRecordView> kept = orderService.dedupOnceGroups(input, configs, result);
        assertEquals(Arrays.asList("b1", "b2", "s1", "s2"), ids(kept));
        assertEquals(0, result.getOrDefault("skippedGroups", 0));
    }

    @Test
    void continuousGroup_execAndEndBothKept() {
        // 持续用药（isOnce=false）：执行+结束 两行都保留，各事件一条文档
        List<Document> configs = Collections.singletonList(config("124", false));
        List<IcuRecordView> input = Arrays.asList(
                row("c1", "BC7", "执行", "124", t(10)),
                row("c2", "BC7", "结束", "124", t(20)));
        Map<String, Integer> result = new HashMap<>();
        List<IcuRecordView> kept = orderService.dedupOnceGroups(input, configs, result);
        assertEquals(Arrays.asList("c1", "c2"), ids(kept));
        assertEquals(0, result.getOrDefault("skippedGroups", 0));
    }

    @Test
    void onceGroup_execAndEndKeepsOnlyExec() {
        // 单次用药（isOnce=true）：组内有执行+结束时只留执行行
        List<Document> configs = Collections.singletonList(config("104", true));
        List<IcuRecordView> input = Arrays.asList(
                row("o1", "BC8", "执行", "104", t(10)),
                row("o2", "BC8", "结束", "104", t(20)));
        Map<String, Integer> result = new HashMap<>();
        List<IcuRecordView> kept = orderService.dedupOnceGroups(input, configs, result);
        assertEquals(Collections.singletonList("o1"), ids(kept));
        assertEquals(0, result.getOrDefault("skippedGroups", 0));
    }

    @Test
    void mixedGroups_onlyOnceGroupsAreDeduped() {
        List<Document> configs = Collections.singletonList(config("104", true));
        List<IcuRecordView> input = Arrays.asList(
                // isOnce 组：只留执行行
                row("o1", "BC5", "复核", "104", t(10)),
                row("o2", "BC5", "执行", "104", t(20)),
                // 非 isOnce 组：全部保留，且保持输入顺序
                row("x1", "BC6", "执行", "1", t(30)),
                row("x2", "BC6", "执行", "1", t(40)));
        Map<String, Integer> result = new HashMap<>();
        List<IcuRecordView> kept = orderService.dedupOnceGroups(input, configs, result);
        assertEquals(Arrays.asList("o2", "x1", "x2"), ids(kept));
        assertEquals(0, result.getOrDefault("skippedGroups", 0));
    }

    /* ==== 非单次用药标记与快推记录 ==== */

    @Test
    void dedupOnceGroups_collectsNonOnceKeptRowsOnly() {
        List<Document> configs = Arrays.asList(config("1", false), config("104", true));
        List<IcuRecordView> input = Arrays.asList(
                row("o1", "BC1", "执行", "104", t(10)),   // isOnce 组：不进 nonOnce
                row("n1", "BC2", "执行", "1", t(20)),      // isOnce=false 组：整组进 nonOnce
                row("n2", "BC2", "结束", "1", t(30)),
                row("b1", null, "执行", "1", t(40)));      // 空 barcode：按自身供药码判定，进 nonOnce
        Set<IcuRecordView> nonOnce = Collections.newSetFromMap(new IdentityHashMap<>());
        List<IcuRecordView> kept = orderService.dedupOnceGroups(input, configs, new HashMap<>(), nonOnce);
        assertEquals(Arrays.asList("o1", "n1", "n2", "b1"), ids(kept));
        assertEquals(3, nonOnce.size());
        assertFalse(nonOnce.contains(input.get(0)));
    }

    @Test
    void fastPushExeId_incrementsTrailingDigits() {
        assertEquals("20261023_20261015002413_1_120261023142928",
                OrderService.fastPushExeId("20261023_20261015002413_1_120261023142927"));
        assertEquals("e2", OrderService.fastPushExeId("e1"));
        assertEquals("e10", OrderService.fastPushExeId("e9"));
        // 进位去前导零时补零保持原数字段长度
        assertEquals("abc008", OrderService.fastPushExeId("abc007"));
    }

    @Test
    void fastPushExeId_nullWhenNoTrailingDigits() {
        assertNull(OrderService.fastPushExeId("abcdef"));
        assertNull(OrderService.fastPushExeId(null));
        assertNull(OrderService.fastPushExeId("  "));
    }

    @Test
    void buildExecuteDocs_nonOnceWithAmountGeneratesFastPushDoc() {
        IcuRecordView r = row("e1", "BC1", "执行", "124", t(10));
        r.setFastPushAmount("10");
        r.setNurseUsername("李艳");
        r.setSupplyName("微量泵注入");
        Map<String, Integer> result = new HashMap<>();
        List<Document> docs = orderService.buildExecuteDocs(
                Collections.singletonList(r), Collections.singleton(r), result);

        assertEquals(2, docs.size());
        // 常规文档：exeId/status 不变，无 inAmount
        Document base = docs.get(0);
        assertEquals("e1", base.getString("exeId"));
        assertEquals("执行", base.getString("status"));
        assertFalse(base.containsKey("inAmount"));
        // 快推文档：exeId+1、status=快推、inAmount=fast_push_amount，其余字段与源记录一致
        Document fast = docs.get(1);
        assertEquals("e2", fast.getString("exeId"));
        assertEquals("快推", fast.getString("status"));
        assertEquals("10", fast.getString("inAmount"));
        assertEquals("BC1", fast.getString("barCode"));
        assertEquals("g-e1", fast.getString("orderID"));
        assertEquals("李艳", fast.getString("nurseTrueName"));
        assertEquals("李艳", fast.getString("nurseUserName"));
        assertEquals("124", fast.getString("supplyCode"));
        assertEquals("微量泵注入", fast.getString("supplyName"));
        assertEquals(t(10), fast.getDate("planTime"));
        assertEquals(t(10), fast.getDate("exeTime"));
        assertEquals(1, result.getOrDefault("fastPush", 0));
    }

    @Test
    void buildExecuteDocs_skipsWhenOnceOrAmountEmpty() {
        IcuRecordView once = row("o1", "BC1", "执行", "104", t(10));
        once.setFastPushAmount("10"); // 单次用药：不生成快推
        IcuRecordView blank = row("n1", "BC2", "执行", "1", t(20));
        blank.setFastPushAmount("  "); // 空字符串：不生成快推
        IcuRecordView empty = row("n2", "BC3", "执行", "1", t(30));
        empty.setFastPushAmount(null); // null：不生成快推
        Map<String, Integer> result = new HashMap<>();
        Set<IcuRecordView> nonOnce = Collections.newSetFromMap(new IdentityHashMap<>());
        nonOnce.add(blank);
        nonOnce.add(empty);
        // once 不在 nonOnce 中；blank/empty 在但金额为空
        List<Document> docs = orderService.buildExecuteDocs(
                Arrays.asList(once, blank, empty), nonOnce, result);
        assertEquals(3, docs.size());
        assertEquals(0, result.getOrDefault("fastPush", 0));
    }

    @Test
    void buildExecuteDocs_skipsFastPushWhenExeIdCollidesInBatch() {
        IcuRecordView r1 = row("e1", "BC1", "执行", "124", t(10));
        r1.setFastPushAmount("10"); // e1+1=e2，与批内另一条源记录冲突
        IcuRecordView r2 = row("e2", "BC2", "执行", "124", t(20));
        Map<String, Integer> result = new HashMap<>();
        Set<IcuRecordView> nonOnce = Collections.newSetFromMap(new IdentityHashMap<>());
        nonOnce.add(r1);
        nonOnce.add(r2);
        List<Document> docs = orderService.buildExecuteDocs(Arrays.asList(r1, r2), nonOnce, result);
        // 冲突时只保留两条常规文档，不生成快推，避免覆盖真实事件
        assertEquals(2, docs.size());
        assertEquals("e1", docs.get(0).getString("exeId"));
        assertEquals("e2", docs.get(1).getString("exeId"));
        assertEquals(0, result.getOrDefault("fastPush", 0));
    }
}
