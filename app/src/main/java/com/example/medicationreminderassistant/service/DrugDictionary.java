package com.example.medicationreminderassistant.service;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * 药品标准库：内置常见药品通用名，用于
 * 1) 校验 OCR 提取的名字是不是药品（过滤"用法/查看图片"等垃圾文字）；
 * 2) 对形近错别字做模糊纠错（如 利巴书林颗粒 -> 利巴韦林颗粒）。
 * 数据来源于国家基本药物目录/常见 OTC 目录的常用品名，离线内置、随版本更新扩充。
 */
public final class DrugDictionary {

    private DrugDictionary() {
    }

    /** 剂型后缀：以这些结尾的名字大概率是药品 */
    private static final String[] FORM_SUFFIXES = {
            "缓释片", "控释片", "分散片", "肠溶片", "泡腾片", "咀嚼片", "含片",
            "软胶囊", "肠溶胶囊", "缓释胶囊", "胶囊", "片",
            "颗粒", "冲剂", "口服液", "口服溶液", "合剂", "糖浆", "混悬液", "干混悬剂",
            "丸", "滴丸", "散", "酊", "膏", "乳膏", "软膏", "眼膏", "凝胶",
            "栓", "贴", "贴剂", "贴片", "气雾剂", "喷雾剂", "吸入剂", "粉雾剂",
            "滴眼液", "滴鼻液", "滴耳液", "洗剂", "搽剂", "注射液", "粉针剂"
    };

    /** 屏幕 UI/处方栏目等常见误识别词：出现即判定不是药名 */
    private static final Set<String> JUNK_WORDS = new HashSet<>(Arrays.asList(
            "用法", "用量", "用法用量", "查看", "图片", "搜索", "保存", "标记", "点击",
            "患处", "适量", "外用", "口服", "每日", "每次", "一天", "一天内",
            "处方", "医嘱", "诊断", "姓名", "性别", "年龄", "日期", "科室", "医师",
            "药品", "药物", "规格", "数量", "单位", "金额", "合计", "费用", "以下空白",
            "首页", "返回", "确认", "取消", "编辑", "删除", "添加", "分享", "扫一扫"
    ));

    /** 常见药品标准名（含剂型），用于纠错与命中校验 */
    private static final String[] STANDARD_NAMES = {
            // 抗感染
            "阿莫西林胶囊", "阿莫西林颗粒", "头孢克肟颗粒", "头孢克肟分散片", "头孢克洛胶囊",
            "头孢呋辛酯片", "头孢地尼胶囊", "头孢氨苄胶囊", "阿奇霉素片", "阿奇霉素颗粒",
            "罗红霉素胶囊", "克拉霉素缓释片", "克拉霉素片", "利巴韦林颗粒", "利巴韦林片",
            "左氧氟沙星片", "诺氟沙星胶囊", "甲硝唑片", "替硝唑片", "奥司他韦胶囊",
            "阿昔洛韦片", "利奈唑胺片", "盐酸溴己新片", "氨溴索口服液", "氨溴索片",
            // 呼吸/感冒
            "板蓝根颗粒", "感冒清热颗粒", "连花清瘟胶囊", "布洛芬缓释胶囊", "布洛芬混悬液",
            "对乙酰氨基酚片", "复方氨酚烷胺片", "氨酚黄那敏颗粒", "右美沙芬糖浆",
            "氯雷他定片", "西替利嗪滴剂", "孟鲁司特钠咀嚼片", "沙丁胺醇气雾剂",
            "藿香正气口服液", "藿香正气水", "小柴胡颗粒", "双黄连口服液", "抗病毒口服液",
            // 消化
            "奥美拉唑肠溶胶囊", "雷贝拉唑钠肠溶片", "铝碳酸镁咀嚼片", "多潘立酮片",
            "蒙脱石散", "双歧杆菌三联活菌胶囊", "乳果糖口服溶液", "开塞露", "莫沙必利片",
            "复方消化酶胶囊", "肠炎宁片", "藿香正气胶囊",
            // 心脑血管/慢病
            "硝苯地平控释片", "氨氯地平片", "缬沙坦胶囊", "氯沙坦钾片", "厄贝沙坦片",
            "美托洛尔缓释片", "阿托伐他汀钙片", "瑞舒伐他汀钙片", "阿司匹林肠溶片",
            "氯吡格雷片", "硝酸甘油片", "单硝酸异山梨酯片", "二甲双胍片", "二甲双胍缓释片",
            "格列美脲片", "阿卡波糖片", "胰岛素注射液",
            // 五官/口腔/皮肤
            "口腔炎喷雾剂", "西瓜霜含片", "复方氯己定含漱液", "碘甘油",
            "左氧氟沙星滴眼液", "妥布霉素滴眼液", "红霉素眼膏", "玻璃酸钠滴眼液",
            "莫匹罗星软膏", "红霉素软膏", "炉甘石洗剂", "地奈德乳膏", "酮康唑洗剂",
            // 神经/精神/其他
            "艾司唑仑片", "阿普唑仑片", "佐匹克隆片", "甲钴胺片", "维生素B1片",
            "维生素C片", "维生素D滴剂", "碳酸钙D3片", "葡萄糖酸钙口服液",
            "叶酸片", "硫酸亚铁片", "地屈孕酮片", "左甲状腺素钠片",
            "苯溴马隆片", "非布司他片", "秋水仙碱片", "塞来昔布胶囊", "双氯芬酸钠缓释片"
    };

    private static final Set<String> STANDARD_SET = new HashSet<>(Arrays.asList(STANDARD_NAMES));

    /** 判断提取出的名字是否"像药品"（用于过滤垃圾行） */
    public static boolean isPlausibleDrugName(String name) {
        if (name == null) {
            return false;
        }
        String n = name.trim();
        if (n.length() < 2 || n.length() > 20) {
            return false;
        }
        // 命中标准库：直接认可
        if (STANDARD_SET.contains(n)) {
            return true;
        }
        // 含垃圾词：拒绝（"囚查看图片""用法外涂患处"等）
        for (String junk : JUNK_WORDS) {
            if (n.contains(junk)) {
                // 例外："口服"出现在"口服液"剂型名里不算垃圾
                if ("口服".equals(junk) && n.contains("口服液")) {
                    continue;
                }
                return false;
            }
        }
        // 与标准名差 1 个字以内：认可（等待纠错）
        if (bestMatchDistance(n) <= 1) {
            return true;
        }
        // 以剂型后缀结尾：认可（如 "XX牌益生菌颗粒"）
        for (String suffix : FORM_SUFFIXES) {
            if (n.endsWith(suffix) && n.length() > suffix.length()) {
                return true;
            }
        }
        return false;
    }

    /** 模糊纠错：与标准名编辑距离 <=2 时返回标准名，否则返回原名 */
    public static String correctName(String name) {
        if (name == null) {
            return null;
        }
        String n = name.trim();
        if (STANDARD_SET.contains(n)) {
            return n;
        }
        String best = null;
        int bestDist = Integer.MAX_VALUE;
        for (String std : STANDARD_NAMES) {
            int d = levenshtein(n, std);
            if (d < bestDist) {
                bestDist = d;
                best = std;
            }
        }
        // 差 1~2 个字才纠（避免误纠），且长度接近
        if (best != null && bestDist <= 2 && Math.abs(best.length() - n.length()) <= 1) {
            return best;
        }
        return n;
    }

    private static int bestMatchDistance(String name) {
        int best = Integer.MAX_VALUE;
        for (String std : STANDARD_NAMES) {
            int d = levenshtein(name, std);
            if (d < best) {
                best = d;
            }
        }
        return best;
    }

    /** 经典编辑距离（名字都很短，性能足够） */
    private static int levenshtein(String a, String b) {
        int[] prev = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) {
            prev[j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            int[] curr = new int[b.length() + 1];
            curr[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                curr[j] = Math.min(Math.min(curr[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            prev = curr;
        }
        return prev[b.length()];
    }
}
