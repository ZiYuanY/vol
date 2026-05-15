package test_data;

import com.example.volsystem.model.black.BlackScholesFormula;
import com.example.volsystem.model.black.BlackScholesOptionType;
import com.example.volsystem.model.optimization.CalibrationResult;
import com.example.volsystem.model.svi.SviParameters;
import com.example.volsystem.model.svi.SviSmile;
import com.example.volsystem.model.svi.calibration.SviCalibrationData;
import com.example.volsystem.model.svi.calibration.SviCalibrator;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

// 读取原始期权 CSV，分组后 SVI 校准，并输出CSV。
public final class SviCalibrationCsvScipt {

    // 输入原始期权数据 CSV。
    private static final Path INPUT = Path.of("src/main/java/test_data/mo_option_original_data.csv");
    // 输出增强后的 CSV（追加年化时间、市场隐波、SVI 参数、SVI 理论隐波）。
    private static final Path OUTPUT = Path.of("src/main/java/test_data/mo_option_original_data_with_svi.csv");
    private static final double YEAR_BASIS = 365.0;
    private static final double DISCOUNT_RATE = 0.02;   //r

    private SviCalibrationCsvScipt() {
    }

    public static void main(String[] args) throws IOException {
        // 1) 读取原始 CSV。
        List<Row> rows = readRows(INPUT);
        // 2) 逐行计算年化时间与市场隐含波动率。
        computeYearFractionAndMarketIv(rows);
        // 3) 分组拟合 SVI 参数，并回填理论隐波。
        fitSviByGroup(rows);
        // 4) 计算理论期权价格（未贴现）
        computeTheoPrice(rows);
        // 5) 输出带新增字段的新 CSV。
        writeRows(rows, OUTPUT);
        System.out.println("Done. Output written to: " + OUTPUT.toAbsolutePath());
    }

    // 读取原始 CSV，并把每一行解析成 Row。
    private static List<Row> readRows(Path input) throws IOException {
        try (BufferedReader reader = Files.newBufferedReader(input, StandardCharsets.UTF_8)) {
            String headerLine = reader.readLine();
            if (headerLine == null) {
                throw new IllegalArgumentException("CSV is empty: " + input);
            }
            List<String> headers = parseCsvLine(headerLine);
            Map<String, Integer> idx = indexMap(headers);

            List<Row> rows = new ArrayList<>();
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                List<String> cols = parseCsvLine(line);
                Row row = new Row();
                row.originalValues = cols;
                row.headers = headers;
                row.tradingDay = LocalDate.parse(value(cols, idx, "trading_day"));
                row.expirationDate = LocalDate.parse(value(cols, idx, "expiration_date"));
                row.optionTypeRaw = value(cols, idx, "option_type");
                row.strike = Double.parseDouble(value(cols, idx, "strike_price"));
                row.optionPrice = Double.parseDouble(value(cols, idx, "close_price"));
                row.forward = Double.parseDouble(value(cols, idx, "f_price"));
                rows.add(row);
            }
            return rows;
        }
    }

    // 年化时间 = (到期日 - 交易日) / 365；同时用 BS 反解市场隐含波动率。
    private static void computeYearFractionAndMarketIv(List<Row> rows) {
        for (Row row : rows) {
            long days = ChronoUnit.DAYS.between(row.tradingDay, row.expirationDate);
            row.yearFraction = Math.max(days / YEAR_BASIS, 0.0);

            try {
                BlackScholesOptionType optionType=parseOptionType(row.optionTypeRaw);
//                BlackScholesOptionType optionType = chooseOptionTypeByMoneyness(row.strike, row.forward, row.optionTypeRaw);
                row.marketIv = BlackScholesFormula.impliedVolatilityFromForward(
                        optionType,
                        row.optionPrice,
                        row.strike,
                        row.forward,
                        row.yearFraction,
                        Math.exp(-DISCOUNT_RATE * row.yearFraction));
            } catch (Exception e) {
                row.marketIv = Double.NaN;
            }
        }
    }

    // 每个交易日 + 到期日是一组，组内用有效点拟合一套 SVI 参数。
    private static void fitSviByGroup(List<Row> rows) {
        Map<String, List<Row>> groups = rows.stream()
                .collect(Collectors.groupingBy(row -> row.tradingDay + "|" + row.expirationDate));

        SviCalibrator calibrator = new SviCalibrator();

        for (List<Row> groupRows : groups.values()) {
            List<Row> validRows = groupRows.stream()
                    .filter(r -> r.yearFraction > 0.0)
                    .filter(r -> Double.isFinite(r.marketIv) && r.marketIv > 0.0)
                    .filter(r -> r.strike > 0.0)
                    // 旧逻辑：不过滤期权类型，全部有效样本都参与拟合。
                    // .filter(r -> true)
                    // 新逻辑：按 moneyness 选样本：K >= F 只用 Call，K < F 只用 Put。
                    .filter(r -> isConsistentWithMoneynessForFit(r.strike, r.forward, r.optionTypeRaw))
                    .collect(Collectors.toList());

            if (validRows.size() < 5) {
                clearGroup(groupRows);
                continue;
            }

            double[] strikes = validRows.stream().mapToDouble(r -> r.strike).toArray();
            double[] marketVols = validRows.stream().mapToDouble(r -> r.marketIv).toArray();
            double expiry = validRows.get(0).yearFraction;
            double forward = validRows.get(0).forward;

            double atmIv = marketVols[nearestStrikeIndex(strikes, forward)];
//            SviParameters initialGuess = new SviParameters(
//                    Math.max(1.0e-4, atmIv * atmIv * expiry * 0.25),
//                    0.5,
//                    0.2,
//                    -0.2,
//                    0.0);
            SviParameters initialGuess= new SviParameters(
                    0.04,
                    0.1,
                    0.1,
                    -0.3,
                    0
            );

            SviParameters fitted;
            try {
                SviCalibrationData data = new SviCalibrationData(forward, expiry, strikes, marketVols, null);
                CalibrationResult<SviParameters> result = calibrator.calibrate(data, initialGuess);
                fitted = result.parameters();
            } catch (Exception e) {
                fitted = null;
            }

            for (Row row : groupRows) {
                if (fitted == null) {
                    row.sviA = Double.NaN;
                    row.sviB = Double.NaN;
                    row.sviSigma = Double.NaN;
                    row.sviRho = Double.NaN;
                    row.sviM = Double.NaN;
                    row.sviTheoIv = Double.NaN;
                    continue;
                }

                row.sviA = fitted.a();
                row.sviB = fitted.b();
                row.sviSigma = fitted.sigma();
                row.sviRho = fitted.rho();
                row.sviM = fitted.m();

                try {
                    row.sviTheoIv = new SviSmile(row.yearFraction, row.forward, fitted).volatility(row.strike);
                } catch (Exception e) {
                    row.sviTheoIv = Double.NaN;
                }
            }
        }
    }

    // 当前分组无法稳定拟合时，清空该组的 SVI 结果列。
    private static void clearGroup(List<Row> groupRows) {
        for (Row row : groupRows) {
            row.sviA = Double.NaN;
            row.sviB = Double.NaN;
            row.sviSigma = Double.NaN;
            row.sviRho = Double.NaN;
            row.sviM = Double.NaN;
            row.sviTheoIv = Double.NaN;
        }
    }


    //计算理论期权价格(未贴现)
    private static void computeTheoPrice(List<Row> rows){
        for(Row row :rows){
            BlackScholesOptionType optionType=parseOptionType(row.optionTypeRaw);
            try{
                row.sviTheoPrice=BlackScholesFormula.priceFromForward(
                        optionType,
                        row.strike,
                        row.forward,
                        row.yearFraction,
                        row.sviTheoIv
                );
            }catch (Exception e){
                row.sviTheoPrice=Double.NaN;
            }
        }
    }


    // 输出原始字段 + 计算字段 + SVI 拟合结果。
    private static void writeRows(List<Row> rows, Path output) throws IOException {
        if (rows.isEmpty()) {
            throw new IllegalArgumentException("no rows to write");
        }

        List<String> baseHeaders = rows.get(0).headers;
        List<String> headers = new ArrayList<>(baseHeaders);
        headers.addAll(Arrays.asList(
                "year_fraction",
                "market_iv",
                "svi_a",
                "svi_b",
                "svi_sigma",
                "svi_rho",
                "svi_m",
                "svi_theoretical_iv",
                "svi_theoretical_price"));

        try (BufferedWriter writer = Files.newBufferedWriter(output, StandardCharsets.UTF_8)) {
            writer.write(toCsvLine(headers));
            writer.newLine();

            for (Row row : rows) {
                List<String> out = new ArrayList<>(row.originalValues);
                out.add(formatDouble(row.yearFraction));
                out.add(formatDouble(row.marketIv));
                out.add(formatDouble(row.sviA));
                out.add(formatDouble(row.sviB));
                out.add(formatDouble(row.sviSigma));
                out.add(formatDouble(row.sviRho));
                out.add(formatDouble(row.sviM));
                out.add(formatDouble(row.sviTheoIv));
                out.add(formatDouble(row.sviTheoPrice));
                writer.write(toCsvLine(out));
                writer.newLine();
            }
        }
    }

    private static String value(List<String> cols, Map<String, Integer> idx, String key) {
        Integer i = idx.get(key);
        if (i == null || i >= cols.size()) {
            throw new IllegalArgumentException("missing required column: " + key);
        }
        return cols.get(i).trim();
    }

    private static Map<String, Integer> indexMap(List<String> headers) {
        Map<String, Integer> map = new HashMap<>();
        for (int i = 0; i < headers.size(); i++) {
            map.put(headers.get(i).trim(), i);
        }
        return map;
    }

//    private static BlackScholesOptionType chooseOptionTypeByMoneyness(double strike, double forward, String fallbackRawType) {
//        if (strike > forward) {
//            return BlackScholesOptionType.CALL;
//        }
//        if (strike < forward) {
//            return BlackScholesOptionType.PUT;
//        }
//        return parseOptionType(fallbackRawType);
//    }

    private static BlackScholesOptionType chooseOptionTypeByMoneyness(double strike, double forward, String fallbackRawType) {
        if (strike >= forward) {
            return BlackScholesOptionType.CALL;
        }
        return BlackScholesOptionType.PUT;
    }

    private static BlackScholesOptionType parseOptionType(String raw) {
        String value = raw.trim().toUpperCase();
        if ("C".equals(value)) {
            return BlackScholesOptionType.CALL;
        }
        if ("P".equals(value)) {
            return BlackScholesOptionType.PUT;
        }
        throw new IllegalArgumentException("unsupported option type: " + raw);
    }

    private static boolean isConsistentWithMoneynessForFit(double strike, double forward, String rawOptionType) {
        if (strike >= forward) {
            return "C".equalsIgnoreCase(rawOptionType);
        }
        return "P".equalsIgnoreCase(rawOptionType);
    }

    private static int nearestStrikeIndex(double[] strikes, double forward) {
        int best = 0;
        double minDistance = Double.POSITIVE_INFINITY;
        for (int i = 0; i < strikes.length; i++) {
            double d = Math.abs(strikes[i] - forward);
            if (d < minDistance) {
                minDistance = d;
                best = i;
            }
        }
        return best;
    }

    private static String formatDouble(double value) {
        if (!Double.isFinite(value)) {
            return "";
        }
        return Double.toString(value);
    }

    private static List<String> parseCsvLine(String line) {
        List<String> values = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;

        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') {
                if (inQuotes && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    current.append('"');
                    i++;
                } else {
                    inQuotes = !inQuotes;
                }
            } else if (c == ',' && !inQuotes) {
                values.add(current.toString());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        values.add(current.toString());
        return values;
    }

    private static String toCsvLine(List<String> cols) {
        return cols.stream().map(SviCalibrationCsvScipt::escapeCsv).collect(Collectors.joining(","));
    }

    private static String escapeCsv(String value) {
        String safe = value == null ? "" : value;
        boolean needQuotes = safe.contains(",") || safe.contains("\"") || safe.contains("\n") || safe.contains("\r");
        if (!needQuotes) {
            return safe;
        }
        return '"' + safe.replace("\"", "\"\"") + '"';
    }

    private static final class Row {
        private List<String> headers;
        private List<String> originalValues;
        private LocalDate tradingDay;
        private LocalDate expirationDate;
        private String optionTypeRaw;
        private double strike;
        private double optionPrice;
        private double forward;

        private double yearFraction;
        private double marketIv;
        private double sviA;
        private double sviB;
        private double sviSigma;
        private double sviRho;
        private double sviM;
        private double sviTheoIv;

        private double sviTheoPrice;
    }
}
