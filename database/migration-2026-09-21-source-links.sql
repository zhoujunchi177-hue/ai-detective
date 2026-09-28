-- ============================================================================
-- 迁移：修复失效的「真实资料」来源链接（2026-09-21）
--
-- 问题：玩家点击「文件」标签页里的资料来源，或「线索」「时间线」里的来源链接时，
--       打开的新标签页加载失败。9 条来源里 6 条有问题，其中 4 条是 404 死链。
--       链接同时存在于三张表：case_sources / clues / case_timeline。
--
-- 分两批（见第 1~3 节与第 5 节）：
--   第一批：明确 404 的死链 —— 换到同机构的新地址，或换到真正报道该事实的媒体。
--   第二批：**链接能打开但指错地方**，以及**引用本身不存在**。
--           其中 case_sources #2 的 BBC 引用是伪造的：该 URL 返回 404，
--           Wayback 无任何快照，站内搜索零结果 —— 属于「为现实悬案伪造来源」，
--           比死链更严重，必须换成真实可核验的报道并同步改署名。
--
-- 为什么必须写这个文件：data.sql 只管「全新安装」。已存在的库不会被重跑 data.sql，
-- 所以不写迁移，老库和新库的链接就会分叉（老库全是死链）。
--
-- 原则：换了机构的，来源标签/类型/可靠性必须同步改 —— 否则等于把 CNN 的报道
--       署名成 FBI，那是伪造来源，比死链更严重。
--
-- 幂等：全部用 `WHERE 旧值` 的 UPDATE，重复执行不会出错、也不会有副作用。
--
-- 用法：
--   mysql --host=127.0.0.1 --port=3307 --user=root --database=mindtrace \
--         --default-character-set=utf8mb4 \
--         < database/migration-2026-09-21-source-links.sql
-- ============================================================================

USE mindtrace;

-- ---------------------------------------------------------------------------
-- 1) case_sources #9：原「FBI: Zodiac Killer cipher solved」指向的 FBI 页面已 404。
--    FBI 没有可用的等价页面，改用报道该结论的 CNN 文章，
--    因此 name / source_type / source_reliability / description 一并更正。
-- ---------------------------------------------------------------------------
UPDATE case_sources
SET source_name        = 'CNN: Zodiac Killer cipher solved',
    source_url         = 'https://www.cnn.com/2020/12/11/us/zodiac-killer-cypher-340-code-trnd',
    source_type        = 'MAINSTREAM_MEDIA',
    source_reliability = 'HIGH',
    description        = '主流媒体对 FBI 确认 Z340 密文由多方密码分析人员破解的公开报道。'
WHERE source_name = 'FBI: Zodiac Killer cipher solved'
  AND source_url  = 'https://www.fbi.gov/news/stories/cryptanalysis-solves-zodiac-killer-cipher';

-- ---------------------------------------------------------------------------
-- 2) 同一死链在 clues / case_timeline 里各有一处，来源标签原为 'FBI'，
--    换成 CNN 后标签必须跟着改。
-- ---------------------------------------------------------------------------
UPDATE clues
SET source_name = 'CNN'
WHERE source_url  = 'https://www.fbi.gov/news/stories/cryptanalysis-solves-zodiac-killer-cipher'
  AND source_name = 'FBI';

UPDATE case_timeline
SET source_name = 'CNN'
WHERE source_url  = 'https://www.fbi.gov/news/stories/cryptanalysis-solves-zodiac-killer-cipher'
  AND source_name = 'FBI';

UPDATE clues
SET source_url = 'https://www.cnn.com/2020/12/11/us/zodiac-killer-cypher-340-code-trnd'
WHERE source_url = 'https://www.fbi.gov/news/stories/cryptanalysis-solves-zodiac-killer-cipher';

UPDATE case_timeline
SET source_url = 'https://www.cnn.com/2020/12/11/us/zodiac-killer-cypher-340-code-trnd'
WHERE source_url = 'https://www.fbi.gov/news/stories/cryptanalysis-solves-zodiac-killer-cipher';

-- ---------------------------------------------------------------------------
-- 3) 其余：同机构、同类页面，只换 URL，来源标签保持不动（仍然准确）。
-- ---------------------------------------------------------------------------

-- CNN 2013-02-21 那篇已 404，换成同日同题的另一篇（已验证 200）。
-- 出现在 case_sources / clues ×2 / case_timeline ×2。
UPDATE case_sources  SET source_url = 'https://www.cnn.com/2013/02/20/us/california-hotel-water-corpse'
  WHERE source_url = 'https://www.cnn.com/2013/02/21/us/california-hotel-water-tank-death/index.html';
UPDATE clues         SET source_url = 'https://www.cnn.com/2013/02/20/us/california-hotel-water-corpse'
  WHERE source_url = 'https://www.cnn.com/2013/02/21/us/california-hotel-water-tank-death/index.html';
UPDATE case_timeline SET source_url = 'https://www.cnn.com/2013/02/20/us/california-hotel-water-corpse'
  WHERE source_url = 'https://www.cnn.com/2013/02/21/us/california-hotel-water-tank-death/index.html';

-- 洛杉矶县法医门户已改域，用规范地址免一跳。
UPDATE case_sources  SET source_url = 'https://me.lacounty.gov/' WHERE source_url = 'https://mec.lacounty.gov/';
UPDATE clues         SET source_url = 'https://me.lacounty.gov/' WHERE source_url = 'https://mec.lacounty.gov/';
UPDATE case_timeline SET source_url = 'https://me.lacounty.gov/' WHERE source_url = 'https://mec.lacounty.gov/';

-- FBI 网站改版：旧 /history/famous-cases/... 路径 404，用新路径。
UPDATE case_sources  SET source_url = 'https://www.fbi.gov/history/cases-and-criminals/black-dahlia'
  WHERE source_url = 'https://www.fbi.gov/history/famous-cases/black-dahlia';
UPDATE clues         SET source_url = 'https://www.fbi.gov/history/cases-and-criminals/black-dahlia'
  WHERE source_url = 'https://www.fbi.gov/history/famous-cases/black-dahlia';
UPDATE case_timeline SET source_url = 'https://www.fbi.gov/history/cases-and-criminals/black-dahlia'
  WHERE source_url = 'https://www.fbi.gov/history/famous-cases/black-dahlia';

-- 黄道十二宫：FBI 的 Famous Cases 页已下线，改用官方档案库 FBI Vault
-- （同一机构，来源标签 'FBI' 仍然准确，可靠性仍为 OFFICIAL）。
UPDATE case_sources  SET source_url = 'https://vault.fbi.gov/The%20Zodiac%20Killer'
  WHERE source_url = 'https://www.fbi.gov/history/famous-cases/zodiac-killer';
UPDATE clues         SET source_url = 'https://vault.fbi.gov/The%20Zodiac%20Killer'
  WHERE source_url = 'https://www.fbi.gov/history/famous-cases/zodiac-killer';
UPDATE case_timeline SET source_url = 'https://vault.fbi.gov/The%20Zodiac%20Killer'
  WHERE source_url = 'https://www.fbi.gov/history/famous-cases/zodiac-killer';

-- 洛杉矶公共图书馆该路径 404，改用照片收藏入口。
UPDATE case_sources  SET source_url = 'https://www.lapl.org/digital-library/lapl-photo-collection'
  WHERE source_url = 'https://www.lapl.org/collections-research';
UPDATE clues         SET source_url = 'https://www.lapl.org/digital-library/lapl-photo-collection'
  WHERE source_url = 'https://www.lapl.org/collections-research';
UPDATE case_timeline SET source_url = 'https://www.lapl.org/digital-library/lapl-photo-collection'
  WHERE source_url = 'https://www.lapl.org/collections-research';

-- 史密森尼杂志改版，用规范地址。
UPDATE case_sources  SET source_url = 'https://www.smithsonianmag.com/category/history/'
  WHERE source_url = 'https://www.smithsonianmag.com/history/';
UPDATE clues         SET source_url = 'https://www.smithsonianmag.com/category/history/'
  WHERE source_url = 'https://www.smithsonianmag.com/history/';
UPDATE case_timeline SET source_url = 'https://www.smithsonianmag.com/category/history/'
  WHERE source_url = 'https://www.smithsonianmag.com/history/';

-- ---------------------------------------------------------------------------
-- 5) 第二批：链接能打开但指错地方 / 引用本身不存在。
--
--    判断依据（都不是猜的）：
--      - bbc.com 对 21510528 返回 404（真实浏览器同样 404）
--      - Wayback availability API 对该 URL 返回空快照
--      - `site:bbc.com "Elisa Lam"` 零结果
--    结论：这条 BBC 引用不存在。换成真实可核验的 CBS News 洛杉矶分台同日报道。
--    换机构 = 署名必须跟着改，否则等于把 CBS 的报道署名给 BBC。
-- ---------------------------------------------------------------------------

-- 5a) 伪造的 BBC 引用 -> CBS News
UPDATE case_sources
SET source_name  = 'CBS News: Body found in water tank identified as Elisa Lam',
    source_url   = 'https://www.cbsnews.com/losangeles/news/body-found-inside-water-tank-atop-downtown-la-hotel/',
    published_at = '2013-02-19',
    description  = '哥伦比亚广播公司洛杉矶分台对遗体发现与身份确认的报道。'
WHERE source_url = 'https://www.bbc.com/news/world-us-canada-21510528';

-- 先改署名（用旧 URL 定位），再改 URL —— 顺序反了就定位不到了。
UPDATE clues
SET source_name = 'LA Times / CBS News'
WHERE source_url  = 'https://www.bbc.com/news/world-us-canada-21510528'
  AND source_name = 'LA Times / BBC';

UPDATE case_timeline
SET source_name = 'LA Times / CBS News'
WHERE source_url  = 'https://www.bbc.com/news/world-us-canada-21510528'
  AND source_name = 'BBC News';

UPDATE clues
SET source_url = 'https://www.cbsnews.com/losangeles/news/body-found-inside-water-tank-atop-downtown-la-hotel/'
WHERE source_url = 'https://www.bbc.com/news/world-us-canada-21510528';

UPDATE case_timeline
SET source_url = 'https://www.cbsnews.com/losangeles/news/body-found-inside-water-tank-atop-downtown-la-hotel/'
WHERE source_url = 'https://www.bbc.com/news/world-us-canada-21510528';

-- 5b) LA Times 旧地址 404 -> 真实归档地址（同机构，标签仍准确），日期对齐。
UPDATE case_sources
SET source_name  = 'Los Angeles Times: Body found in water tank of skid row hotel',
    source_url   = 'https://www.latimes.com/local/la-xpm-2013-feb-20-la-me-body-water-tower-20130220-story.html',
    published_at = '2013-02-20'
WHERE source_url = 'https://www.latimes.com/local/lanow/la-me-ln-body-found-water-tank-cecil-hotel-20130219-story.html';

UPDATE clues
SET source_url = 'https://www.latimes.com/local/la-xpm-2013-feb-20-la-me-body-water-tower-20130220-story.html'
WHERE source_url = 'https://www.latimes.com/local/lanow/la-me-ln-body-found-water-tank-cecil-hotel-20130219-story.html';

UPDATE case_timeline
SET source_url = 'https://www.latimes.com/local/la-xpm-2013-feb-20-la-me-body-water-tower-20130220-story.html'
WHERE source_url = 'https://www.latimes.com/local/lanow/la-me-ln-body-found-water-tank-cecil-hotel-20130219-story.html';

-- 5c) LAPL：数字图书馆首页（罗列数据库入口，与「Black Dahlia archive」标签不符）
--     -> Tessa 地方历史档案门户（实测可访问）。
UPDATE case_sources
SET source_url  = 'https://tessa.lapl.org/',
    description = '洛杉矶公共图书馆数字化地方历史档案（Tessa）入口，可检索报纸、影像与城市历史资料。'
WHERE source_url = 'https://www.lapl.org/digital-library/lapl-photo-collection';

UPDATE clues
SET source_url = 'https://tessa.lapl.org/'
WHERE source_url = 'https://www.lapl.org/digital-library/lapl-photo-collection';

UPDATE case_timeline
SET source_url = 'https://tessa.lapl.org/'
WHERE source_url = 'https://www.lapl.org/digital-library/lapl-photo-collection';

-- 5d) Smithsonian：泛分类页 -> 真正的 Black Dahlia 专题文章（已验证 200）。
UPDATE case_sources
SET source_url   = 'https://www.smithsonianmag.com/smart-news/fresh-look-black-dahlia-murder-180967953/',
    published_at = '2018-01-30'
WHERE source_url = 'https://www.smithsonianmag.com/category/history/';

UPDATE clues
SET source_url = 'https://www.smithsonianmag.com/smart-news/fresh-look-black-dahlia-murder-180967953/'
WHERE source_url = 'https://www.smithsonianmag.com/category/history/';

UPDATE case_timeline
SET source_url = 'https://www.smithsonianmag.com/smart-news/fresh-look-black-dahlia-murder-180967953/'
WHERE source_url = 'https://www.smithsonianmag.com/category/history/';

-- ---------------------------------------------------------------------------
-- 4) 校验：旧 URL 必须一个都不剩（应返回 0 行）；新 URL 应各有若干行。
-- ---------------------------------------------------------------------------
SELECT '残留旧URL总数（应为 0）' AS check_name, COUNT(*) AS cnt
FROM (
    SELECT source_url FROM case_sources
    UNION ALL SELECT source_url FROM clues
    UNION ALL SELECT source_url FROM case_timeline
) AS all_urls
WHERE source_url LIKE '%famous-cases/%'
   OR source_url LIKE '%mec.lacounty.gov%'
   OR source_url LIKE '%collections-research%'
   OR source_url LIKE '%california-hotel-water-tank-death%'
   OR source_url LIKE '%cryptanalysis-solves-zodiac-killer-cipher%'
   OR source_url LIKE '%bbc.com%'
   OR source_url LIKE '%/local/lanow/%'
   OR source_url LIKE '%lapl.org/digital-library%'
   OR (source_url LIKE '%smithsonianmag.com%'
       AND source_url NOT LIKE '%fresh-look-black-dahlia-murder%');

-- 换过机构的，署名必须一起换干净 —— 残留 BBC 署名同样算伪造来源。
SELECT '残留 BBC 署名（应为 0）' AS check_name, COUNT(*) AS cnt
FROM (
    SELECT source_name FROM clues
    UNION ALL SELECT source_name FROM case_timeline
) AS all_names
WHERE source_name LIKE '%BBC%';

SELECT '各来源链接引用数' AS check_name, source_url, COUNT(*) AS cnt
FROM (
    SELECT source_url FROM case_sources
    UNION ALL SELECT source_url FROM clues
    UNION ALL SELECT source_url FROM case_timeline
) AS all_urls
WHERE source_url IS NOT NULL AND source_url <> ''
GROUP BY source_url
ORDER BY cnt DESC;
