USE mindtrace;

SET FOREIGN_KEY_CHECKS = 0;
TRUNCATE TABLE leaderboard_entries;
TRUNCATE TABLE user_achievements;
TRUNCATE TABLE achievements;
TRUNCATE TABLE user_puzzles;
TRUNCATE TABLE puzzles;
TRUNCATE TABLE evidence_relationships;
TRUNCATE TABLE user_clues;
TRUNCATE TABLE chat_messages;
TRUNCATE TABLE investigation_records;
TRUNCATE TABLE game_records;
TRUNCATE TABLE npc_knowledge;
TRUNCATE TABLE npc;
TRUNCATE TABLE case_locations;
TRUNCATE TABLE case_timeline;
TRUNCATE TABLE clues;
TRUNCATE TABLE suspects;
TRUNCATE TABLE case_sources;
TRUNCATE TABLE cases;
TRUNCATE TABLE users;
SET FOREIGN_KEY_CHECKS = 1;

-- 默认测试账号：demo_investigator / demo123
INSERT INTO users
(id, username, nickname, password_hash, avatar, level, exp, coins, completed_cases, streak_days, total_score)
VALUES
(1, 'demo_investigator', '测试调查员', '$2a$10$kmfwTmvlZba7RZVRllRa8OLqZsp99zEoLBsx86sVQE2TX94xLzLfq',
 'avatar-3', 1, 0, 100, 0, 1, 0);

INSERT INTO cases
(id, case_code, title, subtitle, real_name, summary, description, case_type, difficulty, era, location,
 cover_url, status, completion, players, content_rating, real_ratio, adapted_ratio, fictional_ratio)
VALUES
(1, 'CASE-001', '水箱回声', '洛杉矶塞西尔酒店的最后一夜', 'Elisa Lam / 伊莱莎·兰姆案件',
 '2013 年，一名加拿大旅客在洛杉矶市中心酒店失踪。数日后，酒店供水系统出现异常，调查人员在屋顶水箱中发现了她。官方调查倾向意外，但公开视频、酒店环境与时间线留下了持续争议。',
 '本案件依据警方公开信息、验尸官结论和主流媒体档案重构。游戏不会给出与现实冲突的犯罪结论。玩家的目标不是“证明某人杀人”，而是整理失踪前后的可验证时间点、公开证据和信息缺口。',
 '真实悬案', '中等', '2013 年', '美国洛杉矶', '/images/case-elisa-lam.svg', '可调查', 86, 18420, 14, 72.00, 22.00, 6.00),
(2, 'CASE-002', '剪影未署名', '黑色大丽花案的档案缺口', 'Black Dahlia / 黑色大丽花案件',
 '1947 年，伊丽莎白·肖特在洛杉矶遇害，案件迅速成为美国最著名的未结命案之一。大量线索、传闻和自认凶手互相冲突，却没有任何结论能够经得起完整验证。',
 '本案件聚焦公开档案、时间线矛盾和未证实理论。所有人物关系均按“相关人、证人或调查对象”呈现，不把没有司法依据的嫌疑转化为游戏结论。',
 '历史悬案', '较高', '1947 年', '美国洛杉矶', '/images/case-black-dahlia.svg', '可调查', 78, 12610, 16, 80.00, 16.00, 4.00),
(3, 'CASE-003', '北加州的密文', '黄道十二宫杀手的公开档案', 'Zodiac Killer / 黄道十二宫杀手案件',
 '1960 年代末，北加州发生多起袭击。自称“Zodiac”的人向媒体寄送信件、密码和犯罪声明，部分密文已被破解，但凶手身份至今没有获得可靠确认。',
 '本项目只使用 FBI、警方和主流媒体公开信息。玩家可以比较密文、时间线和公开证词，但任何身份猜测都只属于游戏推理假设。',
 '未结案件', '困难', '1968-1969', '美国北加州', '/images/case-zodiac.svg', '可调查', 91, 23180, 18, 88.00, 10.00, 2.00);

INSERT INTO case_sources
(id, case_id, source_name, source_url, source_type, published_at, description, source_reliability)
VALUES
(1, 1, 'Los Angeles Times: Body found in water tank of skid row hotel', 'https://www.latimes.com/local/la-xpm-2013-feb-20-la-me-body-water-tower-20130220-story.html', 'NEWSPAPER', '2013-02-20', '主流报纸对塞西尔酒店水箱发现过程的公开报道。', 'HIGH'),
(2, 1, 'CBS News: Body found in water tank identified as Elisa Lam', 'https://www.cbsnews.com/losangeles/news/body-found-inside-water-tank-atop-downtown-la-hotel/', 'MAINSTREAM_MEDIA', '2013-02-19', '哥伦比亚广播公司洛杉矶分台对遗体发现与身份确认的报道。', 'HIGH'),
(3, 1, 'CNN: Hotel water tank death investigation', 'https://www.cnn.com/2013/02/20/us/california-hotel-water-corpse', 'MAINSTREAM_MEDIA', '2013-02-21', '公开调查进展与酒店供水异常的报道。', 'HIGH'),
(4, 1, 'Los Angeles County Medical Examiner', 'https://me.lacounty.gov/', 'GOVERNMENT', '2013-06-20', '洛杉矶县法医部门公开门户；具体报告需按当地档案规则查询。', 'OFFICIAL'),
(5, 2, 'FBI Famous Cases: Black Dahlia', 'https://www.fbi.gov/history/cases-and-criminals/black-dahlia', 'GOVERNMENT', '2020-01-01', 'FBI 历史案件档案入口，说明案件长期未结及后续线索处理。', 'OFFICIAL'),
(6, 2, 'Los Angeles Public Library: Black Dahlia archive', 'https://tessa.lapl.org/', 'ARCHIVE', '2024-01-01', '洛杉矶公共图书馆数字化地方历史档案（Tessa）入口，可检索报纸、影像与城市历史资料。', 'HIGH'),
(7, 2, 'Smithsonian Magazine: The Black Dahlia', 'https://www.smithsonianmag.com/smart-news/fresh-look-black-dahlia-murder-180967953/', 'MAGAZINE', '2018-01-30', '历史专题对未结案件与流行传闻边界的讨论。', 'HIGH'),
(8, 3, 'FBI Famous Cases: Zodiac Killer', 'https://vault.fbi.gov/The%20Zodiac%20Killer', 'GOVERNMENT', '2025-01-01', 'FBI 公开案件档案，包含确认案件与公开调查状态。', 'OFFICIAL'),
(9, 3, 'CNN: Zodiac Killer cipher solved', 'https://www.cnn.com/2020/12/11/us/zodiac-killer-cypher-340-code-trnd', 'MAINSTREAM_MEDIA', '2020-12-11', '主流媒体对 FBI 确认 Z340 密文由多方密码分析人员破解的公开报道。', 'HIGH');

-- unlock_condition 决定调查地图节点的解锁方式（由后端 Java 计算节点状态）：
--   public                 默认开放
--   clue:CLUE-002          需先发现该线索
--   puzzle:1               需先破解该谜题（按谜题 ID）
--   location:lobby         需先调查该地点
-- 多个条件用逗号分隔，表示「全部满足」。
INSERT INTO case_locations
(id, case_id, location_key, name, description, icon, map_x, map_y, unlock_condition)
VALUES
(1, 1, 'lobby', '酒店大厅与前台', '旅客登记、夜间人员流动和公共区域记录的交汇点。游戏中用虚构夜班人员承载公开档案中的一般性情况。', 'building-2', 18, 68, 'public'),
(2, 1, 'elevator', '客用电梯', '公开监控片段的核心地点。视频存在时间码扰动，且电梯门保持开启的细节无法单独证明结论。', 'scan-line', 38, 50, 'public'),
(3, 1, 'guest-room', '客人与公共档案', '公开档案中的人身与医疗信息只用于解释官方调查，不作为犯罪指控。', 'file-lock-2', 58, 66, 'clue:CLUE-002'),
(4, 1, 'rooftop', '屋顶水箱区域', '酒店屋顶与供水设备。这里的关键问题不是戏剧化场面，而是公开记录中的进入条件与发现顺序。', 'container', 77, 25, 'clue:CLUE-006'),
(5, 1, 'water-system', '供水系统与投诉记录', '住客对水压和水质的投诉触发了检查，之后才发现水箱中的遗体。', 'droplets', 84, 54, 'location:lobby'),
(6, 2, 'last-seen', '最后出现地点', '伊丽莎白·肖特生命最后阶段可能涉及的洛杉矶公共场所。公开记录对“最后一次确认看见”存在差异。', 'map-pin', 34, 38, 'public'),
(7, 2, 'discovery-site', '发现区域', '1947 年 1 月 15 日发现遗体的雷默特公园区域。游戏不进行血腥画面描述。', 'map', 58, 56, 'public'),
(8, 2, 'newsroom', '洛杉矶报刊档案', '本案名称、报道和大量传闻的传播中心，也是检验信息可靠性的关键。', 'newspaper', 76, 28, 'location:last-seen'),
(9, 2, 'grand-jury', '大陪审团档案', '1949 年大陪审团相关程序留下的公开材料，但不能等同为最终真相。', 'landmark', 72, 72, 'clue:CLUE-022'),
(10, 3, 'lake-herman', 'Lake Herman Road', '1968 年首批确认案件相关地点。游戏只呈现公开警方摘要。', 'route', 22, 38, 'public'),
(11, 3, 'blue-rock', 'Blue Rock Springs', '1969 年确认案件相关地点与公开证词的时间交点。', 'map-pin', 45, 62, 'public'),
(12, 3, 'lake-berryessa', 'Lake Berryessa', '案件模式与公开陈述发生转折的地点。', 'waves', 66, 35, 'location:blue-rock'),
(13, 3, 'presidio', 'Presidio Heights', '城市区域案件与后续通信线索的交汇。', 'building', 80, 70, 'location:lake-berryessa'),
(14, 3, 'cipher-desk', '密码分析工作台', '信件、密码和报纸公开材料的结构化分析区域。', 'binary', 53, 20, 'clue:CLUE-031');

INSERT INTO npc
(id, case_id, npc_key, name, avatar, description, personality, identity, location, greeting, hidden_information, relationship, content_type)
VALUES
(1, 1, 'night-clerk', '米娅·托雷斯', 'npc-ma', '游戏虚构的酒店夜班档案助理。她的信息来自公开报道重组，不代表现实中的任何具体酒店员工。', '谨慎、克制、话不多，对媒体叙事保持警惕', '酒店公共区域档案助理（虚构角色）', '酒店大厅与前台', '嗯……你也是为了那件事来的吧。值班记录我这儿有一份，你想问哪段？', '不会主动透露未公开理论。', '协助玩家核对公共区域时间线', 'FICTIONAL'),
(2, 1, 'researcher', '林墨', 'npc-lin', '独立档案研究者，负责区分公开事实、媒体推测和网络传闻。', '理性、直接、坚持来源分级', '公开资料研究者（虚构角色）', '客人与公共档案', '先别急着下结论。你手上的东西，哪些是公开事实、哪些只是传闻，先分一分，我们再聊。', '掌握一条关于电梯片段争议的结构化提示。', '提供证据评估，不代表警方立场', 'FICTIONAL'),
(3, 1, 'maintenance', '周师傅', 'npc-zhou', '游戏虚构的维护记录整理员，只了解供水设备与投诉处理流程。', '务实、少言、只谈设备流程', '建筑维护记录整理员（虚构角色）', '供水系统与投诉记录', '设备不会讲故事，只会留下压力、时间和检修记录。', '没有目击犯罪或身份信息。', '解释供水系统工作方式', 'FICTIONAL'),
(4, 2, 'archive-editor', '伊芙琳·凯恩', 'npc-evelyn', '游戏虚构的报纸档案编辑，熟悉 1947 年新闻传播环境。', '老练、审慎、对耸动标题反感', '报刊档案编辑（虚构角色）', '洛杉矶报刊档案', '报纸可以把案件变成传奇，但传奇不是证据。你想核对哪条时间线？', '知道媒体命名如何影响案件记忆。', '帮助分辨新闻报道与后续传闻', 'FICTIONAL'),
(5, 2, 'case-researcher', '周弈', 'npc-zhouyi', '游戏虚构的案件档案研究员，只整理公开调查材料与矛盾点。', '冷静、条理清晰、拒绝人名定罪', '公开档案研究员（虚构角色）', '大陪审团档案', '人物关系我可以帮你理，但“曾被询问过”不等于有罪，这两件事别混。', '掌握未证实理论为何反复失败的结构性原因。', '为人物关系和证据可靠性提供游戏内解释', 'FICTIONAL'),
(6, 3, 'cipher-analyst', '诺拉·陈', 'npc-nora', '游戏虚构的密码分析助理。只讨论已公开密文与破解结果。', '逻辑严密、对过度解读不耐烦', '公开密码档案助理（虚构角色）', '密码分析工作台', '密码能说明文本是怎么写的，说明不了是谁写的。你想看哪一段？', '掌握密文字母频率与公开破解过程。', '协助分析密文，不提供凶手身份', 'FICTIONAL'),
(7, 3, 'records-clerk', '马库斯·李', 'npc-marcus', '游戏虚构的案件记录员，只整理跨地区公开案件摘要。', '平实、强调日期与管辖区', '案件记录整理员（虚构角色）', 'Presidio Heights', '这几个警区各写各的时间格式，看着头疼。要对比的话，得先把日期时间对齐。', '不了解未公开的警方嫌疑人名单。', '帮助对齐公开时间线', 'FICTIONAL');

INSERT INTO npc_knowledge
(id, npc_id, knowledge_key, topic, content, disclosure_level)
VALUES
(1, 1, 'checkin-date', '入住与失踪日期', '1 月 26 号入住的，这个登记本上写着。后来大概 2 月 1 号前后，人就报失踪了。至于最后一次有人看见她到底是什么时候……我看到的几种说法对不上，我不敢说准。', 'normal'),
(2, 1, 'public-areas', '公共区域记录', '我这儿能翻的，基本就是大厅、电梯这些公共区域的监控，还有后来新闻里转的那点东西。至于这到底算不算案子、算哪种案子，不是我一个值夜班的能说的。', 'normal'),
(3, 1, 'elevator-video', '电梯监控片段', '电梯那段我看过好多遍。她在里面按了好几次按钮，门一直没关。还有就是……时间码好像不太对，哪儿不对我说不上来。', 'normal'),
(4, 1, 'elevator-anomaly', '电梯门的异常', '门为什么一直开着？我也想过。可能是设备的事，也可能是有人在按。再多的我就不知道了。网上那些说法太玄了，我不太信。', 'normal'),
(5, 2, 'official-cause', '官方死因表述', '法医那边的公开说法偏向意外溺水，也提到过她的双相障碍。那只是官方的结论，不代表就没有别的说法。', 'normal'),
(6, 2, 'online-theories', '网络传闻', '网上关于这部电梯、那个屋顶、还有这家酒店历史的说法多得吓人。问题是它们老把都市传说、没核实的转述和真事搅在一起，很难分。', 'normal'),
(7, 2, 'evidence-boundary', '证据边界', '按现在能公开拿到的东西，既证不了有他杀，也排除不掉别的可能。所以只能看你的假设和手上的线索对不对得上。', 'normal'),
(8, 3, 'water-flow', '供水与发现顺序', '先是住客说水压不对、水还有味，然后才有人上屋顶开箱看，结果就发现了。这个顺序是清楚的。人是怎么进去的，我这儿没记录。', 'normal'),
(9, 3, 'roof-access', '屋顶进入条件', '屋顶那道门，报纸上写过好几种版本，有的说锁着，有的说报警器坏了。我看到的材料对不上，所以我也说不准。', 'normal'),
(10, 4, 'media-name', '案件命名的传播影响', '“黑色大丽花”这个名字是报纸推出来的，后来大家就都这么叫了。听着像个定论，可司法文件里并没有这么写。', 'normal'),
(11, 4, 'news-reliability', '1947 年报道环境', '那会儿报纸抢版面抢得厉害。早期那些报道里，没核实的警方消息、目击者转述、还有纯粹为了抢先的猜测，都混着用。', 'normal'),
(12, 5, 'unsolved-status', '案件状态', '这案子到现在也没有一个被广泛认可的定案结论。被查过、被怀疑过、被报纸点过名，都不等于有罪 —— 这点我一直很在意。', 'normal'),
(13, 5, 'suspect-noise', '大量嫌疑人线索', '这些年冒出来一堆自认的、传的、还有各种理论，很多互相打架。理人物关系的时候，“公开接触过”“被问过话”“有证据支持”，这三样得分开看。', 'normal'),
(14, 6, 'z340', 'Z340 密文', 'Z340 是 2020 年才破的，FBI 说是好几个国家的密码分析人员一起弄出来的。破出来是有一段话，但写信的人到底是谁，还是没定。', 'normal'),
(15, 6, 'cipher-boundary', '密文与身份', '笔迹、拼写、纸张这些东西，能说明这些信可能是同一个人写的，但说明不了这个人现实中是谁。', 'normal'),
(16, 7, 'jurisdiction', '跨辖区时间线', '这案子跨了好几个警区，每个警区写日期、编案号的方式都不一样。要对比的话，得先把日期、地点、来源都对齐了，再看行为。', 'normal'),
(17, 7, 'official-scope', '确认与声明边界', 'FBI 的公开档案里，一部分是确认过的案件，另一部分是大量自称者寄来的“认罪”声明。这两堆东西不能算成一回事。', 'normal');

INSERT INTO clues
(id, case_id, clue_code, title, content, type, importance, source_type, source_name, source_url,
 unlock_condition, location_key, npc_id, keyword, is_real, is_hidden)
VALUES
(1, 1, 'CLUE-001', '公开失踪时间线', '公开报道可确认：2013 年 1 月 26 日入住，约 2 月 1 日前后失踪；遗体于 2 月 19 日在酒店屋顶水箱中被发现。', 'TIMELINE', 5, 'REAL', 'LA Times / CBS News', 'https://www.cbsnews.com/losangeles/news/body-found-inside-water-tank-atop-downtown-la-hotel/', 'initial', NULL, NULL, NULL, 1, 0),
(2, 1, 'CLUE-002', '入住与离开记录', '酒店登记与公开报道建立了最初时间锚点。不同材料对“最后被看见”的描述并不完全一致，应记录为区间而不是精确到分钟。', 'DOCUMENT', 4, 'REAL', 'LA Times', 'https://www.latimes.com/local/la-xpm-2013-feb-20-la-me-body-water-tower-20130220-story.html', 'location', 'lobby', NULL, NULL, 1, 0),
(3, 1, 'CLUE-003', '电梯监控片段', '公开视频显示伊莱莎·兰姆在电梯内多次操作按钮，电梯门保持开启。视频存在时间码异常，且公开版本可能经过剪辑。', 'VIDEO', 5, 'REAL', 'CNN / LA Times', 'https://www.cnn.com/2013/02/20/us/california-hotel-water-corpse', 'location', 'elevator', NULL, NULL, 1, 0),
(4, 1, 'CLUE-004', '电梯门的解释边界', '电梯门持续开启可以引出设备、操作和时间码三类解释。没有控制记录时，不能把任一解释直接当成事实。', 'CONTRADICTION', 4, 'ADAPTED', '游戏改编分析', NULL, 'npc_keyword', NULL, 1, '电梯', 0, 0),
(5, 1, 'CLUE-005', '酒店公共区域环境', '塞西尔酒店位于洛杉矶市中心，历史上治安与居住环境复杂。这个背景会影响公众叙事，却不能自动证明本案中存在犯罪。', 'PERSON', 3, 'ADAPTED', '公开资料整理', NULL, 'location', 'lobby', NULL, NULL, 1, 0),
(6, 1, 'CLUE-006', '住客水质投诉', '在遗体被发现前，住客曾报告水压、水味或供水异常。维护检查因此展开，并最终指向屋顶水箱。', 'TIMELINE', 5, 'REAL', 'CNN', 'https://www.cnn.com/2013/02/20/us/california-hotel-water-corpse', 'location', 'water-system', NULL, NULL, 1, 0),
(7, 1, 'CLUE-007', '维护检查流程', '供水异常触发的是设备检查流程。维护记录只能说明发现顺序，不能回答人物如何进入水箱区域。', 'DOCUMENT', 3, 'ADAPTED', '游戏改编维护档案', NULL, 'location', 'water-system', NULL, NULL, 0, 0),
(8, 1, 'CLUE-008', '水箱发现记录', '2013 年 2 月 19 日，维护人员在屋顶消防水箱中发现了失踪者。发现过程有多个媒体版本，核心日期高度一致。', 'TIMELINE', 5, 'REAL', 'LA Times', 'https://www.latimes.com/local/la-xpm-2013-feb-20-la-me-body-water-tower-20130220-story.html', 'location', 'rooftop', NULL, NULL, 1, 0),
(9, 1, 'CLUE-009', '屋顶进入条件', '关于屋顶门、报警装置和进入路径的公开叙述存在差异。这段差异属于关键信息缺口，需要与监控和设备记录交叉验证。', 'CONTRADICTION', 5, 'ADAPTED', '公开资料整理', NULL, 'location', 'rooftop', NULL, NULL, 0, 0),
(10, 1, 'CLUE-010', '官方死因倾向', '洛杉矶县法医公开结论倾向意外溺水，并将双相障碍列为重要背景因素。该结论不代表所有公众疑问都得到解释。', 'FORENSIC', 5, 'REAL', 'Los Angeles County Medical Examiner', 'https://me.lacounty.gov/', 'location', 'guest-room', NULL, NULL, 1, 0),
(11, 1, 'CLUE-011', '医疗背景的边界', '公开资料提到精神状态和处方信息，但医疗背景只能帮助理解风险，不能在没有证据时推导他人行为或犯罪动机。', 'PERSON', 4, 'REAL', '公开验尸信息转述', NULL, 'location', 'guest-room', NULL, NULL, 1, 0),
(12, 1, 'CLUE-012', '网络理论的来源污染', '大量网络理论混合了酒店历史、超自然叙事和其他案件。即使某些细节引人注意，也必须先确认它是否来自可核验的原始材料。', 'CONTRADICTION', 4, 'ADAPTED', '游戏改编证据评估', NULL, 'npc_keyword', NULL, 2, '网络', 0, 0),
(13, 1, 'CLUE-013', '电梯时间戳与行为矛盾', '若把视频时间码视为绝对准确，人物行为顺序会与部分公开叙事冲突；若时间码经过处理，则不能据此推导超常解释。它更像一条需要验证的时间线矛盾。', 'CONTRADICTION', 5, 'FICTIONAL', '游戏谜题推演', NULL, 'puzzle', NULL, NULL, NULL, 0, 1),
(14, 1, 'CLUE-014', '酒店历史不等于本案证据', '塞西尔酒店曾发生其他事件，但这只能解释公众为何投射恐怖叙事，不能为本案提供直接因果证据。', 'PERSON', 3, 'ADAPTED', '公开历史资料', NULL, 'puzzle', NULL, NULL, NULL, 0, 1),

(20, 2, 'CLUE-020', '最后确认时间存在差异', '公开档案对伊丽莎白·肖特最后一次被可靠看见的时间、地点存在不同叙述，需将“最后确认”与“后来传闻”区分。', 'TIMELINE', 5, 'REAL', 'FBI / LAPL', 'https://www.fbi.gov/history/cases-and-criminals/black-dahlia', 'initial', NULL, NULL, NULL, 1, 0),
(21, 2, 'CLUE-021', '发现日期锚点', '1947 年 1 月 15 日，遗体在洛杉矶雷默特公园一带被发现。该日期是所有后续时间线的重要锚点。', 'TIMELINE', 5, 'REAL', 'FBI', 'https://www.fbi.gov/history/cases-and-criminals/black-dahlia', 'location', 'discovery-site', NULL, NULL, 1, 0),
(22, 2, 'CLUE-022', '媒体名称的传播', '“黑色大丽花”并非司法案件名称，而是媒体报道与传播形成的标签。标签影响了证人和公众对案件的记忆。', 'DOCUMENT', 4, 'REAL', '报刊档案', NULL, 'location', 'newsroom', NULL, NULL, 1, 0),
(23, 2, 'CLUE-023', '早期报道可靠性', '1940 年代新闻竞争激烈，未经核实的目击信息、警方传闻和夸张标题可能同时出现。需要按发布时间排列而不是只看内容。', 'CONTRADICTION', 4, 'ADAPTED', '游戏改编档案说明', NULL, 'npc_keyword', NULL, 4, '标题', 0, 0),
(24, 2, 'CLUE-024', '大陪审团程序记录', '1949 年的相关程序留下公开材料，但程序存在本身不能证明任何具体人物实施了犯罪。', 'DOCUMENT', 4, 'REAL', '洛杉矶公共档案', 'https://tessa.lapl.org/', 'location', 'grand-jury', NULL, NULL, 1, 0),
(25, 2, 'CLUE-025', '嫌疑人信息噪声', '多年间出现大量自认、传闻和理论。被调查、被媒体点名或主动声称与案件有关，都不等于司法确认。', 'CONTRADICTION', 5, 'ADAPTED', '公开资料整理', NULL, 'npc_keyword', NULL, 5, '关系', 0, 0),
(26, 2, 'CLUE-026', '未结案件状态', 'FBI 与地方档案均将案件视为长期未结。游戏不提供“现实真凶”答案，只评价玩家依据公开材料构建的推理链。', 'DOCUMENT', 5, 'REAL', 'FBI', 'https://www.fbi.gov/history/cases-and-criminals/black-dahlia', 'puzzle', NULL, NULL, NULL, 1, 1),

(30, 3, 'CLUE-030', 'FBI 确认案件范围', 'FBI 公开档案列出若干确认案件，同时记录大量自称者发出的声明。确认案件与自认内容必须分开。', 'DOCUMENT', 5, 'REAL', 'FBI', 'https://vault.fbi.gov/The%20Zodiac%20Killer', 'initial', NULL, NULL, NULL, 1, 0),
(31, 3, 'CLUE-031', '跨年度攻击时间线', '公开确认案件集中在 1968 至 1969 年，发生地点跨越多个北加州辖区。统一日期和地点后才能分析模式。', 'TIMELINE', 5, 'REAL', 'FBI', 'https://vault.fbi.gov/The%20Zodiac%20Killer', 'location', 'lake-herman', NULL, NULL, 1, 0),
(32, 3, 'CLUE-032', '公众通信与报纸', '自称者向多家报纸寄送信件、密码和犯罪声明，使媒体成为案件信息传播链的一部分。', 'DOCUMENT', 4, 'REAL', 'FBI / 报刊档案', 'https://vault.fbi.gov/The%20Zodiac%20Killer', 'location', 'cipher-desk', NULL, NULL, 1, 0),
(33, 3, 'CLUE-033', 'Z340 的破解边界', 'Z340 于 2020 年由多国密码分析人员合作破解。文本内容不等于写信人身份得到确认。', 'FORENSIC', 5, 'REAL', 'CNN', 'https://www.cnn.com/2020/12/11/us/zodiac-killer-cypher-340-code-trnd', 'npc_keyword', NULL, 6, '密文', 1, 0),
(34, 3, 'CLUE-034', '身份仍未确认', 'FBI 公开信息没有确认凶手身份。任何具名猜测都属于调查假设，而不是现实结论。', 'DOCUMENT', 5, 'REAL', 'FBI', 'https://vault.fbi.gov/The%20Zodiac%20Killer', 'puzzle', NULL, NULL, NULL, 1, 1);

INSERT INTO case_timeline
(id, case_id, event_time, event_date_text, title, description, people, location, source_name, source_url, content_type, sort_order)
VALUES
(1, 1, '2013-01-26 00:00:00', '2013-01-26', '入住塞西尔酒店', '公开报道显示伊莱莎·兰姆入住洛杉矶市中心塞西尔酒店。', 'Elisa Lam', 'Cecil Hotel, Los Angeles', 'LA Times / CBS News', 'https://www.cbsnews.com/losangeles/news/body-found-inside-water-tank-atop-downtown-la-hotel/', 'REAL', 10),
(2, 1, '2013-01-31 00:00:00', '2013-01-31（时间码存在争议）', '电梯监控片段', '公开监控片段显示兰姆在电梯内操作按钮，门保持开启。时间码与公开版本完整性存在讨论。', 'Elisa Lam', 'Hotel elevator', 'LA Times / CNN', 'https://www.cnn.com/2013/02/20/us/california-hotel-water-corpse', 'REAL', 20),
(3, 1, '2013-02-01 00:00:00', '2013-02-01 前后', '被列为失踪人员', '公开报道将其失踪时间定位在 2 月 1 日前后。精确的“最后看见”时刻并未获得单一一致材料支持。', 'Hotel staff / LAPD', 'Los Angeles', 'LA Times / CBS News', 'https://www.cbsnews.com/losangeles/news/body-found-inside-water-tank-atop-downtown-la-hotel/', 'REAL', 30),
(4, 1, '2013-02-06 00:00:00', '2013-02-06', '警方公开监控录像', '洛杉矶警方公开电梯监控片段，希望公众提供线索，视频随后引发大量讨论。', 'LAPD / Public', 'Los Angeles', 'CNN', 'https://www.cnn.com/2013/02/20/us/california-hotel-water-corpse', 'REAL', 40),
(5, 1, '2013-02-19 00:00:00', '2013-02-19', '屋顶水箱发现遗体', '住客投诉供水异常后，维护人员检查屋顶水箱并发现失踪者。', 'Maintenance staff / LAPD', 'Cecil Hotel rooftop', 'LA Times', 'https://www.latimes.com/local/la-xpm-2013-feb-20-la-me-body-water-tower-20130220-story.html', 'REAL', 50),
(6, 1, '2013-02-21 00:00:00', '2013-02-21', '身份确认与调查推进', '法医部门推进身份确认，警方同步调查死因与屋顶进入情况。', 'Coroner / LAPD', 'Los Angeles County', 'LA Times / CBS News', 'https://www.cbsnews.com/losangeles/news/body-found-inside-water-tank-atop-downtown-la-hotel/', 'REAL', 60),
(7, 1, NULL, '2013 年公开结果', '官方倾向意外溺水', '洛杉矶县法医公开结论倾向意外溺水，并将双相障碍列为重要背景因素；公众争议没有因此完全消失。', 'Los Angeles County Medical Examiner', 'Los Angeles County', 'LA County Medical Examiner', 'https://me.lacounty.gov/', 'REAL', 70),
(8, 1, NULL, '游戏推演节点', '时间码扰动假设', '玩家将视频时间码、电梯门行为和公开叙事排序后可形成一种游戏假设；它不是现实案件结论。', 'Game analysis', 'MindTrace archive', '游戏改编', NULL, 'FICTIONAL', 80),

(20, 2, '1947-01-09 00:00:00', '1947-01-09 前后', '最后公开行踪', '档案对伊丽莎白·肖特最后可靠出现的地点和时间存在差异，日历图需要按来源标注。', 'Elizabeth Short', 'Los Angeles', 'FBI / LAPL', 'https://www.fbi.gov/history/cases-and-criminals/black-dahlia', 'REAL', 10),
(21, 2, '1947-01-15 00:00:00', '1947-01-15', '发现遗体', '遗体在洛杉矶雷默特公园附近被发现，案件进入警方调查。', 'Witness / LAPD', 'Leimert Park area', 'FBI', 'https://www.fbi.gov/history/cases-and-criminals/black-dahlia', 'REAL', 20),
(22, 2, NULL, '1947 年初', '媒体命名扩散', '报刊报道把案件塑造成“黑色大丽花”，名称和叙述迅速扩散。', 'Newspapers / Public', 'Los Angeles', 'Newspaper archive', NULL, 'REAL', 30),
(23, 2, NULL, '1947 年以后', '大量线索与自认', '警方与媒体收到大量互相冲突的线索和自认，绝大多数无法形成可靠案件结论。', 'LAPD / Public', 'Los Angeles', 'FBI', 'https://www.fbi.gov/history/cases-and-criminals/black-dahlia', 'REAL', 40),
(24, 2, '1949-01-01 00:00:00', '1949 年', '大陪审团相关程序', '案件相关程序产生公开材料，但并不等同于法院定罪或现实真相确认。', 'Grand jury / Officials', 'Los Angeles', 'Public archive', 'https://tessa.lapl.org/', 'REAL', 50),
(25, 2, NULL, '至今', '案件仍未结', 'FBI 与地方档案仍将案件列为未结，游戏结局只代表玩家推理。', 'Historical record', 'Los Angeles', 'FBI', 'https://www.fbi.gov/history/cases-and-criminals/black-dahlia', 'REAL', 60),

(30, 3, '1968-12-20 00:00:00', '1968-12-20', 'Lake Herman Road 案件', 'FBI 公开案件时间线中的早期确认案件。', 'Victims / Investigators', 'Benicia area', 'FBI', 'https://vault.fbi.gov/The%20Zodiac%20Killer', 'REAL', 10),
(31, 3, '1969-07-04 00:00:00', '1969-07-04', 'Blue Rock Springs 案件', '确认案件与公开证词形成后续调查的主要时间点。', 'Victims / Police', 'Vallejo', 'FBI', 'https://vault.fbi.gov/The%20Zodiac%20Killer', 'REAL', 20),
(32, 3, '1969-08-01 00:00:00', '1969-08-01 前后', '报纸收到自称信件', '多家报纸开始收到包含犯罪声明与密码的通信，公共传播成为案件组成部分。', 'Newspapers / Investigators', 'San Francisco Bay Area', 'FBI', 'https://vault.fbi.gov/The%20Zodiac%20Killer', 'REAL', 30),
(33, 3, '1969-09-27 00:00:00', '1969-09-27', 'Lake Berryessa 案件', '案件模式与公开陈述出现变化，成为时间线比较的重要节点。', 'Victims / Witnesses', 'Napa County', 'FBI', 'https://vault.fbi.gov/The%20Zodiac%20Killer', 'REAL', 40),
(34, 3, '1969-10-11 00:00:00', '1969-10-11', 'Presidio Heights 案件', '城市区域案件发生，跨辖区调查进一步展开。', 'Victim / Police', 'San Francisco', 'FBI', 'https://vault.fbi.gov/The%20Zodiac%20Killer', 'REAL', 50),
(35, 3, '2020-12-11 00:00:00', '2020-12-11', 'Z340 密码破解公开', 'FBI 公开说明 Z340 由多国密码分析人员合作破解，但身份仍未确认。', 'FBI / Cryptanalysts', 'International collaboration', 'CNN', 'https://www.cnn.com/2020/12/11/us/zodiac-killer-cypher-340-code-trnd', 'REAL', 60);

INSERT INTO suspects
(id, case_id, name, alias, role, description, relationship, status, evidence_level, content_type)
VALUES
(1, 1, '伊莱莎·兰姆', 'Elisa Lam', '失踪者、案件中心人物', '公开资料确认的失踪者。游戏使用其公开案件信息，不对个人进行负面道德判断。', '案件中心人物', '已确认身份', 'REAL', 'REAL'),
(2, 1, '公开调查人员', NULL, '警方与法医', '多名调查人员参与失踪、发现与法医程序。具体个人不作游戏角色化演绎。', '调查机构', '公开程序参与者', 'REAL', 'REAL'),
(3, 1, '米娅·托雷斯', NULL, '虚构档案助理', '用于承载酒店公共区域一般性资料的游戏角色，不对应现实具体员工。', '协助玩家', '虚构角色', 'FICTIONAL', 'FICTIONAL'),
(4, 2, '伊丽莎白·肖特', 'Black Dahlia', '受害者、案件中心人物', '公开案件中心人物。游戏不复述血腥细节，只呈现档案时间线与传播史。', '案件中心人物', '身份已确认', 'REAL', 'REAL'),
(5, 2, '多次被媒体报道的相关人物', NULL, '档案争议', '由于大量嫌疑人理论互相冲突，此处不列出未经司法确认的现实姓名。', '调查关系人', '未形成可靠定论', 'THEORY', 'ADAPTED'),
(6, 3, '身份未知的自称通信者', 'Zodiac', '通信与犯罪声明来源', 'FBI 公开档案中所指的未知人物，现实身份仍未获得确认。', '案件核心未知项', '未知', 'OFFICIAL', 'REAL'),
(7, 3, '公开案件调查人员', NULL, '跨辖区调查机构', 'FBI 与地方警方公众档案中的调查参与者。', '调查机构', '公开程序参与者', 'REAL', 'REAL');

INSERT INTO puzzles
(id, case_id, puzzle_key, title, description, type, payload, correct_answer, unlock_reward_clue_id, importance)
VALUES
(1, 1, 'timeline-sort', '缺失时间线排序', '把公开时间点按顺序排列。只使用已确认日期，不把争议时间码当作精确时刻。', 'TIME_SORT',
 '{"items":[{"id":"bodyfound","label":"屋顶水箱发现遗体"},{"id":"checkin","label":"入住酒店"},{"id":"complaints","label":"住客投诉供水异常"},{"id":"elevator","label":"电梯监控片段"},{"id":"lastseen","label":"被列为失踪人员"}]}',
 'checkin,elevator,lastseen,complaints,bodyfound', 13, 5),
(2, 1, 'evidence-chain', '供水异常调查链', '选择能够形成公开发现顺序的三项证据。顺序不限，但每项都必须有真实来源支撑。', 'EVIDENCE_LINK',
 '{"items":[{"id":"guest-report","label":"住客报告水质或水压异常"},{"id":"maintenance","label":"维护检查展开"},{"id":"rooftop-tank","label":"屋顶水箱被检查"},{"id":"viral-video","label":"网络视频播放量上升"},{"id":"hotel-history","label":"酒店旧传闻"}]}',
 'guest-report,maintenance,rooftop-tank', 14, 4),
(3, 1, 'elevator-hypothesis', '视频假设判定', '关于电梯门长时间开启，哪一种表述最符合证据边界？', 'PERSON_RELATION',
 '{"choices":[{"id":"supernatural","label":"证明存在超自然力量"},{"id":"unverified","label":"存在设备、操作和时间码等多种未验证解释"},{"id":"murder","label":"直接证明有人实施了犯罪"}]}',
 'unverified', NULL, 4),
(4, 2, 'relationship-boundary', '关系判断边界', '“曾被调查或报道”应如何标注？', 'PERSON_RELATION',
 '{"choices":[{"id":"guilty","label":"等同于有罪"},{"id":"related","label":"只是公开调查关系，不能推定有罪"},{"id":"witness","label":"一律视为目击者"}]}',
 'related', 26, 5),
(5, 3, 'cipher-boundary', '密文能证明什么', '结合 Z340 的公开破解结果，选择最严谨的表述。', 'EVIDENCE_LINK',
 '{"items":[{"id":"text","label":"密文存在可破解文本内容"},{"id":"serial","label":"通信行为可与其他信件关联"},{"id":"identity","label":"破解结果确认了凶手身份"},{"id":"network","label":"可以证明所有自称声明均为真"}]}',
 'text,serial', 34, 5);

INSERT INTO achievements
(id, code, name, description, icon, rarity, reward_exp, reward_coins)
VALUES
(1, 'FIRST_CASE', '第一次调查', '完成并提交第一个案件。', 'folder-check', 'COMMON', 100, 50),
(2, 'FIRST_HIDDEN', '夹层档案', '发现第一条隐藏线索。', 'eye-off', 'LEGENDARY', 80, 40),
(3, 'KEY_CONTRADICTION', '矛盾出现', '发现一条关键矛盾线索。', 'git-compare', 'RARE', 60, 30),
(4, 'TIMELINE_MASTER', '时间线复核者', '完成「缺失时间线排序」谜题。', 'clock-4', 'EPIC', 120, 60);
