package com.bistu.hkgrag.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Prompt 配置类，支持中英文双套 prompt。
 * 通过 application.properties 中的 HKG-RAG.prompt.lang 切换：
 * zh → 中文提示词
 * en → 英文提示词
 */
@Configuration
@ConfigurationProperties(prefix = "hkg-rag.prompt")
public class PromptConfig {

    /**
     * 语言开关：zh / en，默认 zh
     */
    private String lang = "zh";

    public String getLang() {
        return lang;
    }

    public void setLang(String lang) {
        this.lang = lang;
    }

    public boolean isEnglish() {
        return "en".equalsIgnoreCase(lang);
    }

    // ======================== Extraction Prompts ========================

    public String fileNodeExtraction() {
        return isEnglish() ? FileNodeExtraction_EN : FileNodeExtraction_ZH;
    }

    public String contentEntityExtraction() {
        return isEnglish() ? ContentEntityExtraction_EN : ContentEntityExtraction_ZH;
    }

    public String entitiesRelationExtraction() {
        return isEnglish() ? EntitiesRelationExtraction_EN : EntitiesRelationExtraction_ZH;
    }

    public String fileTopicExtraction() {
        return isEnglish() ? FileTopicExtraction_EN : FileTopicExtraction_ZH;
    }

    public String fileTopicSummaryExtraction() {
        return isEnglish() ? FileTopicSummaryExtraction_EN : FileTopicSummaryExtraction_ZH;
    }

    public String topicDescriptionExtraction() {
        return isEnglish() ? TopicDescriptionExtraction_EN : TopicDescriptionExtraction_ZH;
    }

    public String descriptionSummary() {
        return isEnglish() ? DescriptionSummary_EN : DescriptionSummary_ZH;
    }

    public String entityGleaning() {
        return isEnglish() ? EntityGleaning_EN : EntityGleaning_ZH;
    }

    // ======================== Generation Prompts ========================

    public String generationSingleHop() {
        return isEnglish() ? GenerationSingleHop_EN : GenerationSingleHop_ZH;
    }

    public String generationSimpleDocMultiHop() {
        return isEnglish() ? GenerationSimpleDocMultiHop_EN : GenerationSimpleDocMultiHop_ZH;
    }

    public String generationCrossDoc() {
        return isEnglish() ? GenerationCrossDoc_EN : GenerationCrossDoc_ZH;
    }

    public String generationSummaryLevel() {
        return isEnglish() ? GenerationSummaryLevel_EN : GenerationSummaryLevel_ZH;
    }

    // ======================== Agent Prompts ========================

    public String retrievalPlanPrompt() {
        return isEnglish() ? RetrievalPlanPrompt_EN : RetrievalPlanPrompt_ZH;
    }

    public String systemCOT() {
        return isEnglish() ? SystemCOT_EN : SystemCOT_ZH;
    }

    public String retrievalPrompt() {
        return isEnglish() ? RetrievalPrompt_EN : RetrievalPrompt_ZH;
    }

    public String autonomousRetrievalPrompt() {
        return isEnglish() ? AutonomousRetrievalPrompt_EN : AutonomousRetrievalPrompt_ZH;
    }

    public String agentEvaluation() {
        return isEnglish() ? AgentEvaluation_EN : AgentEvaluation_ZH;
    }

    // ======================== Baseline / Assessment Prompts ========================

    public String baselinePrompt() {
        return isEnglish() ? BaselinePrompt_EN : BaselinePrompt_ZH;
    }

    public String intentAnswerPrompt() {
        return isEnglish() ? IntentAnswerPrompt_EN : IntentAnswerPrompt_ZH;
    }

    public String assessUserPrompt() {
        return isEnglish() ? AssessUserPrompt_EN : AssessUserPrompt_ZH;
    }

    // ================================================================
    //  ====================  Chinese Prompts  ========================
    // ================================================================

    private static final String FileNodeExtraction_ZH = """
            # 角色：你是一个知识图谱构建助手。
            
            ## 基本信息
            -语言：中文
            -描述：你擅长从文档名和文档内容中提取文档的元信息和摘要。
            -任务：从以下文档名和文档内容中抽取文档节点信息。
            
            ## 目标
            1. 将整篇文档作为一个实体节点输出（只输出一个实体）。
            2. entityName 使用文档名称（如"北京信息科技大学2015年端午节值班表"）。
            3. entityDescription 为文档的内容摘要，用自然语言概述文档涉及的核心业务、主要事项或关键信息，不超过150字。
               **注意**：entityDescription 侧重描述文档"讲了什么有哪些重要信息"。
            4. entityInfo 存储文档的元信息，格式为 "key1:value1; key2:value2; ..."，包含以下字段（只抽取能在文本中明确找到的）：
               - 发文时间：文档的发布日期或落款日期
               - 发布机构：文档的发布单位或发文机关
               - 抄送单位：文档的抄报/抄送单位
               - 文号：文档的发文字号
               - 其他关键属性
            5. 只抽取能在文本中明确找到的信息，不要随意生成。
            6. 强证据锚定，无重复或推测。
            
            ## 输入格式
            文档名称："%s"
            文档内容："%s"
            
            ## 输出格式
            请输出完整的 JSON 格式，包含一个名为 "entities" 的数组（只有一个元素）：
            {
              "entities": [
                {
                  "entityName": "文档名称",
                  "entityDescription": "文档摘要，不超过50字",
                  "entityInfo": "发文时间:xxxx; 发布机构:xxxx; 抄送单位:xxxx"
                }
              ]
            }
            
            注意事项：
            1. 输出完整 JSON 对象，不要额外的文本或注释。
            2. entities 数组中只包含一个文档节点。
            3. entityInfo 中用分号分隔各字段，字段格式为"字段名:字段值"。
            4. 如果某个属性为空，该字段不出现在 entityInfo 中。
            5. 如果某个字段为空，使用 ""。
            ######################
            - 示例 -
            ######################
            示例 1：
            文档名称：北京信息科技大学2015年端午节值班表
            文档内容：北京信息科技大学2015年端午节值班表一、干部值班表时间星期带班校领导值班人员6月20日六林国策朱亚媛于东洋6月21日日杨军麻力李旭6月22日一刘筱毅贺芳祁长青备注：\\n1. 学校总值班： 地点：小营校区3号办公楼学校值班室值班时间：8：30- 12：00，13：00- 16：30值班电话：82426888 ，交接钥匙在值班室。\\n2. 学校24小时值班电话：82426110（安稳处）。\\n3如有紧急情况和重大事件，请与下列人员联系：\\n学校办公室主任： 李华涛18910508898安稳处处长： 胡滔13681127546学生处处长： 郭银辉13522685081健翔桥校区管理办公室主任： 麦苗13651081439清河校区管理办公室主任： 回世勇13910119656东部校区管理办公室主任： 孟创立13366125573二、全校值班人员职责：\\n1.会同保卫和各有关部门做好防火、防盗的各项工作，确保校园安全。\\n2.值班人员必须同时上岗、认真处理日常事务，重要事项应填写电话记录并及时处理。\\n3.值班中发现涉及校园安全等方面的重大事项应及时处理，并立即向有关领导报告。\\n4.坚守岗位，做好值班记录，保持值班室清洁。\\n\\n附件：\\n一、北京信息科技大学安稳处2015年6月值班表二、北京信息科技大学健翔桥校区2015年端午节值班表三、北京信息科技大学清河校区2015年端午节值班表四、北京信息科技大学东部校区2015年端午节值班表五、北京信息科技大学后勤管理处2015年端午节值班表北京信息科技大学学校办公室2015年6月12日抄报：市委教育工委；市教委；西三旗街道办事处、派出所、综治办；亚运村街道办事处、派出所、综治办；北京石油化工学院院办、党办；对外经贸大学校办、党办；\\n发：校领导、各处级单位、校值班室、安稳处值班室。
            ######################
            输出：
            {
              "entities": [
                {
                  "entityName": "北京信息科技大学2015年端午节值班表",
                  "entityDescription": "安排2015年端午节期间（6月20日至22日）学校各校区的干部值班、安全保卫值班工作，明确带班校领导、值班人员名单、值班时间地点、24小时值班电话及突发事件联系人，并附各校区和后勤管理处值班表。",
                  "entityInfo": "发文时间:2015年6月12日; 发布机构:北京信息科技大学学校办公室; 抄送单位:市委教育工委、市教委、西三旗街道办事处等"
                }
              ]
            }
            
            """;

    private static final String ContentEntityExtraction_ZH = """
            # 角色：你是一个知识图谱构建助手。
            
            ## 基本信息
            -语言：中文
            -描述：你擅长从文档内容中全面、细致地提取所有实体，并用自然语言描述每个实体的核心信息。
            -任务：从以下文档内容中**尽可能全面**地抽取出所有实体，并给出每个实体的自然语言描述。
            
            ## 实体类型
            从 [人物, 技术, 任务, 组织, 地点, 事件, 时间, 文档, 概念] 中选择最合适的一个作为实体类型。
            
            ## 目标
            1. **尽可能多地从文档内容中识别出所有命名实体**，包括但不限于：人名、机构名、地名、事件名、时间节点、文件名、制度名、项目名、设施名、电话、文号等。
            2. **不要遗漏任何在文档中明确出现的实体**。即使某个实体看起来不那么重要，只要它是文档中明确提到的命名实体，就应该抽取出来。
            3. 为每个实体生成一段简洁的自然语言描述（entityDescription），该描述应包含实体在文档中出现的核心属性、角色、上下文等信息。
            4. entityInfo 存储该实体的结构化属性信息，格式为 "key1:value1; key2:value2; ..."，包含以下字段（只抽取能在文本中明确找到的）：
               - 对于人物：职务、联系方式、所属部门等
               - 对于组织：地址、联系方式、上级单位等
               - 对于地点：所属区域、功能用途等
               - 对于事件：发生时间、参与方等
               - 对于技术：技术领域、应用场景等
               - 对于任务：责任部门、时间要求等
               - 其他关键属性
            5. 输出 JSON，每条记录包含：
               - entityName：实体名称（如 李华涛、北京信息科技大学、清河校区大礼堂）
               - entityType：实体类型（如 人物、组织、地点、事件）
               - entityDescription：实体的自然语言描述（用一句话概括该实体的核心信息）
               - entityInfo：实体的结构化属性信息
            6. 只抽取能在文本中明确找到的实体和属性，不要随意生成。
            7. 强证据锚定，无重复或推测。
            8. 同一个实体只出现一次，不要为同一实体的不同属性创建多条记录。
            
            ## 输入格式
            文档内容："%s"
            
            ## 输出格式
            请将实体抽取为完整的 JSON 格式，包含一个名为 "entities" 的数组，字段如下：
            {
              "entities": [
                {
                  "entityName": "实体名称",
                  "entityType": "实体类型",
                  "entityDescription": "该实体的自然语言描述",
                  "entityInfo": "职务:校长; 所属部门:北京信息科技大学; 联系方式:18910508898"
                }
              ]
            }
            ######################
            - 示例 -
            ######################
            示例 1：
            文档内容：北京信息科技大学2025年春季开学典礼于清河校区大礼堂举行，校长李华涛主持了典礼。出席典礼的有计算机学院院长王磊、学生处处长郭银辉及各学院代表。
            ######################
            输出：
            {
              "entities": [
                {"entityName": "北京信息科技大学2025年春季开学典礼", "entityType": "事件", "entityDescription": "北京信息科技大学2025年春季开学典礼于清河校区大礼堂举行，由校长李华涛主持，计算机学院院长王磊、学生处处长郭银辉及各学院代表出席", "entityInfo": "举办地点:清河校区大礼堂; 主持人:李华涛"},
                {"entityName": "清河校区大礼堂", "entityType": "地点", "entityDescription": "北京信息科技大学清河校区的大礼堂，是2025年春季开学典礼的举办地点", "entityInfo": "所属校区:清河校区; 用途:举办大型活动"},
                {"entityName": "李华涛", "entityType": "人物", "entityDescription": "北京信息科技大学校长，主持了2025年春季开学典礼", "entityInfo": "职务:校长; 所属组织:北京信息科技大学"},
                {"entityName": "王磊", "entityType": "人物", "entityDescription": "北京信息科技大学计算机学院院长，出席了2025年春季开学典礼", "entityInfo": "职务:计算机学院院长; 所属组织:北京信息科技大学"},
                {"entityName": "郭银辉", "entityType": "人物", "entityDescription": "北京信息科技大学学生处处长，出席了2025年春季开学典礼", "entityInfo": "职务:学生处处长; 所属组织:北京信息科技大学"},
                {"entityName": "北京信息科技大学", "entityType": "组织", "entityDescription": "一所以信息科技为特色的高校，设有清河校区，举办2025年春季开学典礼", "entityInfo": "校区:清河校区"},
                {"entityName": "计算机学院", "entityType": "组织", "entityDescription": "北京信息科技大学下属学院，院长为王磊", "entityInfo": "上级组织:北京信息科技大学; 院长:王磊"}
              ]
            }
            
            注意事项：
            1. 输出完整 JSON 对象，不要额外的文本或注释。
            2. 保持字段顺序和示例一致。
            3. entityDescription 应是一句完整、通顺的自然语言描述。
            4. entityInfo 中用分号分隔各字段，字段格式为"字段名:字段值"。如果某个属性为空，该字段不出现在 entityInfo 中。
            5. 如果某个字段为空，使用 ""。
            6. 尽可能地抽取所有实体，不要遗漏，宁多勿少。
            """;

    private static final String EntitiesRelationExtraction_ZH = """
             # 角色：你是一个知识图谱构建助手。
            
            ## 基本信息
            -语言：中文
            -描述：你擅长根据文档内容抽取文档内部实体之间的关系。
            -任务：根据文档内容抽取文档内容实体与文档内容实体之间的关系。
            
            ## 关系抽取原则
            1. 从用户提供的文档内容和所有文档内部实体中，识别所有明确相关的实体对（源实体 → 目标实体）。
            2. 源实体和目标实体**必须都来自文档内部实体列表**，使用实体名称（name）而非 ID。
            3. 输出 JSON，每条关系需要包含：
               - sourceName：源实体名称
               - targetName：目标实体名称
               - sourceType：源实体类型
               - targetType：目标实体类型
               - description：关系描述（用自然语言描述两个实体之间的具体关联，如"李华涛担任北京信息科技大学校长"）
               - keywords：对源实体名和目标实体名进行泛化后的关键词，用"+"连接。泛化规则：将具体的专有名词替换为能概括其本质的类别词或上位词，保留关系的语义可检索性。例如源实体"李华涛"泛化为"校长"，目标实体"北京信息科技大学"泛化为"大学"，则 keywords 为"校长+大学"。再如"清河校区大礼堂"泛化为"礼堂"，"计算机学院"泛化为"学院"。
            4. 不生成新实体，不修改已有实体名称。
            5. 不重复输出语义相同的关系。
            6. 关系必须能在文档内容中找到**明确文字证据**，禁止推测或泛化。
            7. sourceName 和 targetName 都必须是非空字符串，且必须是两个不同的实体。禁止输出只有单侧实体的无效关系。
            8. 如果文档内只有一个实体或实体间无明显关联，返回空数组 {"relations": []}。
            
            ## 输入格式
            文档内容："%s"
            文档内部实体（JSON 数组，每个对象含 name, type, description, info）："%s"
            
            
            ## 输出格式
            请将关系抽取为完整的 JSON 格式，包含一个名为 "relations" 的数组，字段如下：
            {
              "relations": [
                {
                  "sourceName": "源实体名称",
                  "targetName": "目标实体名称",
                  "sourceType": "源实体类型",
                  "targetType": "目标实体类型",
                  "description": "关系的自然语言描述",
                  "keywords": "源实体泛化词+目标实体泛化词"
                }
              ]
            }
            
            注意事项：
            1. 输出必须是一个完整的 JSON 对象，不要输出 JSONL 或其他额外文本。
            2. 保持字段顺序严格与示例一致。
            3. 如果某个属性为空，使用空字符串 ""。
            4. 数组中的每条信息都是一个对象。
            5. 不要输出任何多余说明、提示或文本。
            6. sourceName 和 targetName 使用实体名称，不要使用 ID。
            
            ######################
            - 示例 -
            ######################
            示例 1：
            输入：
            文档内容：北京信息科技大学2025年春季开学典礼于清河校区大礼堂举行，校长李华涛主持了典礼。出席典礼的有计算机学院院长王磊、学生处处长郭银辉及各学院代表。
            文档内部实体：[
              {"name": "北京信息科技大学2025年春季开学典礼", "type": "事件", "description": "北京信息科技大学2025年春季开学典礼于清河校区大礼堂举行", "info": ""},
              {"name": "清河校区大礼堂", "type": "地点", "description": "清河校区的大礼堂", "info": ""},
              {"name": "李华涛", "type": "人物", "description": "北京信息科技大学校长", "info": ""},
              {"name": "王磊", "type": "人物", "description": "计算机学院院长", "info": ""},
              {"name": "郭银辉", "type": "人物", "description": "学生处处长", "info": ""},
              {"name": "北京信息科技大学", "type": "组织", "description": "信息科技类高校", "info": ""},
              {"name": "计算机学院", "type": "组织", "description": "北京信息科技大学下属学院", "info": ""}
            ]
            ######################
            输出：
            {
              "relations": [
                {"sourceName": "北京信息科技大学2025年春季开学典礼", "targetName": "清河校区大礼堂", "sourceType": "事件", "targetType": "地点", "description": "北京信息科技大学2025年春季开学典礼在清河校区大礼堂举办", "keywords": "开学典礼+礼堂"},
                {"sourceName": "李华涛", "targetName": "北京信息科技大学2025年春季开学典礼", "sourceType": "人物", "targetType": "事件", "description": "校长李华涛主持了2025年春季开学典礼", "keywords": "校长+开学典礼"},
                {"sourceName": "李华涛", "targetName": "北京信息科技大学", "sourceType": "人物", "targetType": "组织", "description": "李华涛担任北京信息科技大学校长", "keywords": "校长+大学"},
                {"sourceName": "王磊", "targetName": "计算机学院", "sourceType": "人物", "targetType": "组织", "description": "王磊担任计算机学院院长", "keywords": "院长+学院"},
                {"sourceName": "计算机学院", "targetName": "北京信息科技大学", "sourceType": "组织", "targetType": "组织", "description": "计算机学院是北京信息科技大学的下属学院", "keywords": "学院+大学"}
              ]
            }
            
            """;

    private static final String FileTopicExtraction_ZH = """
            # 角色：你是一个提炼文章主题的专家。
            
            ## 基本信息
            -语言：中文
            -描述：你擅长根据文档名和文档内容总结文档主题。
            -任务：根据文档名和文档内容总结文档主题。
            -注意：主题与内容相关，主题中不能出现时间、附件、通知、安排、公告、细则、管理、公示等太泛化的词语，不出现组织名和机构名。
            -注意：主题是最能概括文档内容的一个词语或词组
            
            ## 提炼主题的原则
            1. 从用户提供的文档名和文档内容中总结文档主题。
            2. 主题最能反应文档内容，且文档主题不超过30个字。
            ## 输入格式
            文档名："%s"
            文档内容："%s"
            
            
            ## 输出要求
            1. 只输出一个最终主题（词或词组）。
            2. 不要输出任何解释、原因、分析或多余内容。
            3. 不要重复输入中的文档名或文档内容。
            4. 不要输出"输入：""输出："等提示性文字。
            5. 输出中不得包含换行符或多句话。
            6. 输出长度不超过30个字。
            
            ######################
            - 示例 -
            ######################
            示例 1：
            输入：
            文档名：北京信息科技大学2015年端午节值班表.doc
            文档内容：北京信息科技大学2015年端午节值班表一、干部值班表时间星期带班校领导值班人员6月20日六林国策朱亚媛于东洋6月21日日杨军麻力李旭6月22日一刘筱毅贺芳祁长青备注：\\n1. 学校总值班： 地点：小营校区3号办公楼学校值班室值班时间：8：30- 12：00，13：00- 16：30值班电话：82426888 ，交接钥匙在值班室。\\n2. 学校24小时值班电话：82426110（安稳处）。\\n3如有紧急情况和重大事件，请与下列人员联系：\\n学校办公室主任： 李华涛18910508898安稳处处长： 胡滔13681127546学生处处长： 郭银辉13522685081健翔桥校区管理办公室主任： 麦苗13651081439清河校区管理办公室主任： 回世勇13910119656东部校区管理办公室主任： 孟创立13366125573二、全校值班人员职责：\\n1.会同保卫和各有关部门做好防火、防盗的各项工作，确保校园安全。\\n2.值班人员必须同时上岗、认真处理日常事务，重要事项应填写电话记录并及时处理。\\n3.值班中发现涉及校园安全等方面的重大事项应及时处理，并立即向有关领导报告。\\n4.坚守岗位，做好值班记录，保持值班室清洁。\\n\\n附件：\\n一、北京信息科技大学安稳处2015年6月值班表二、北京信息科技大学健翔桥校区2015年端午节值班表三、北京信息科技大学清河校区2015年端午节值班表四、北京信息科技大学东部校区2015年端午节值班表五、北京信息科技大学后勤管理处2015年端午节值班表北京信息科技大学学校办公室2015年6月12日抄报：市委教育工委；市教委；西三旗街道办事处、派出所、综治办；亚运村街道办事处、派出所、综治办；北京石油化工学院院办、党办；对外经贸大学校办、党办；\\n发：校领导、各处级单位、校值班室、安稳处值班室。
            输出：端午节值班
            """;

    private static final String FileTopicSummaryExtraction_ZH = """
            # 角色：你是一个提炼文章主题的高手。
            
            ## 基本信息
            - 语言：中文
            - 描述：你擅长根据文档名和多个分块主题总结文档总体主题。
            - 任务：根据文档名和文档各分块的主题，生成文档最终主题。
            - 注意：多参考文档名，把分块主题看作补充
            
            ## 主题提炼规则
            1. 如果多个分块主题中存在**重复或高度相似的词语**，优先直接使用该词语作为最终主题。
            2. 如果不存在明显重复主题，再对所有分块主题进行归纳总结。
            3. 文档主题应最能概括整体内容。
            4. 文档主题不超过30个字。
            5. 不要重复输入内容，不要解释原因。
            
            ## 输入格式
            文档名："%s"
            各分块主题：
            %s
            
            ## 输出要求
            - 只输出一个词或词组，作为文档最终主题。
            - 不要带任何描述、标点或示例说明。
            - 字数不超过30个字。
            
            
            ######################
            - 示例 -
            ######################
            示例1：
            输入：
            文档名：北京信息科技大学2015年端午节值班表.doc
            各分块主题：
                - 干部值班安排
                - 校园安全值班
                - 值班电话与职责
                - 校区值班表
            输出：端午节值班
            
            示例2:
            输入：
            文档名：2023年研究生招生工作方案.doc
            各分块主题：
            - 研究生招生流程
            - 招生录取要求
            - 研究生招生安排
            输出：研究生招生
            """;

    private static final String TopicDescriptionExtraction_ZH = """
            #角色：你是一个专业的信息总结的专家，擅长从多个文档中抽取共性信息并进行总体总结。
            
            [任务说明]
            给定同一话题簇下的多个文档信息（文档主题、文档名、文档内容），请你综合所有文档信息，生成该话题簇的簇名和簇内文档内容的总体描述
            1.综合该话题簇下的所有文档，总结这个话题簇的核心主题、主要内容、共同关注点
            2.保留共性观点和重要细节
            3.去除无关细节
            4.簇名精炼简短，贴合核心内容，4-10字为宜
            5.总体描述控制在100字以内，语句通顺、贴合高校工作场景
            6.严格按照指定格式输出，不添加额外解释、无关话术
            
            ##输入数据
            每个文档包含：
            - 主题（topic）
            - 文档名（title）
            - 文档内容（content）
            
            待总结的文档信息如下：
            %s
            
            输出：
            簇名：
            描述：
            ##########################
            示例1：
            输入：
            主题：毕业生离校手续，文档名：清华大学2025届毕业生离校办理须知，内容：明确毕业生图书归还、宿舍退宿、公物交接等流程，安排专人对接办理，提速离校手续办结。
            主题：毕业生离校结算管理，文档名：清华大学2025届毕业生费用清算通知，内容：核对学费、住宿费等相关费用，办结财务结算、离校审批手续，保障毕业生顺利离校离校。
            
            标准输出：
            【簇名】毕业生离校工作
            【描述】明确清华大学2025届毕业生图书归还、宿舍退宿、公物交接等办理流程，同步核对各类费用、办结财务结算与离校审批等流程。
            """;

    private static final String DescriptionSummary_ZH = """
            # 角色：你是一个专业的文本整理专家，擅长对拼接而成的描述进行去冗余和语言润色。
            
            [任务说明]
            下面是一段由多段描述拼接而成的文本，请对其整理优化。
            要求：
            1. 合并相同或相似的表述，去除冗余，但不丢失任何关键信息点
            2. 保留所有关键信息点（如地点、用途、所属单位、时间、人员、具体细节等），不得遗漏
            3. 输出通顺流畅的中文描述，不要刻意压缩字数，信息完整优先
            4. 不要使用 +、| 等分隔符，用自然语言衔接各信息点
            
            ##########################
            示例：
            输入：
            一教是北京信息科技大学的教学楼之一 + 北京信息科技大学的一教楼，仪器科学与光电工程学院的教学楼 | 北京信息科技大学的教学楼之一，主要供仪器科学与光电工程学院使用，位于小营校区
            
            输出：
            一教是北京信息科技大学的教学楼之一，位于小营校区，主要供仪器科学与光电工程学院使用，同时也是该学院的教学与办公场所。
            
            ## 原始描述
            %s
            
            ## 整理后的描述
            """;

    private static final String EntityGleaning_ZH = """
            # 角色：你是一个知识图谱构建助手。
            
            ## 基本信息
            -语言：中文
            -描述：你擅长从文档内容中补充发现遗漏的实体。
            -任务：给定文档内容和已抽取的实体列表，识别是否有遗漏的重要实体。
            
            ## 原则
            1. 仔细阅读文档内容，逐句检查已抽取的实体列表是否完整。
            2. **重点关注**：文档中明确出现但未在列表中的人名、机构名、地名、事件名、时间节点、文件/附件名、制度名、项目名、电话号码、文号等。
            3. 只补充那些在文档中明确出现、且确实有独立信息价值的实体。例如：一个列表中列出的每个人名、每个部门、每个地点都应该单独作为实体。
            4. 不要重复已抽取的实体。
            5. 如果没有遗漏实体，返回空数组。
            
            ## 输入格式
            文档内容："%s"
            已抽取实体列表：%s
            
            ## 输出格式
            请以完整的 JSON 格式输出，包含一个名为 "entities" 的数组：
            {
              "entities": [
                {
                  "entityName": "遗漏的实体名称",
                  "entityType": "实体类型",
                  "entityDescription": "该实体的自然语言描述",
                  "entityInfo": ""
                }
              ]
            }
            
            注意事项：
            1. 输出完整 JSON 对象，不要额外的文本或注释。
            2. 如果没有遗漏实体，输出 {"entities": []}。
            3. entityType 从 [人物, 技术, 任务, 组织, 地点, 事件, 时间, 文档, 概念] 中选择。
            """;

    private static final String GenerationSingleHop_ZH = """
            #角色：你是一个高质量问答数据集生成助手，用于构建检索增强生成（RAG）系统的问答测试数据。
            # 任务：请根据给定文档名和文档内容生成1-3条单跳问答对并给出文档中对应的依据。
            # 定义
            单跳问题定义：
                - 只需要文档中的一个信息点即可回答
                - 不需要推理、计算或多信息组合
            # 要求
                - 问题必须是自然语言提问并且比较详细
                - 答案必须可以在文档中直接找到
                - 禁止编造文档中不存在的信息
                - according为问题答案对的文档原文
            
            #输入
            文档名：%s
            文档内容：%s
            
            # 输出格式（严格JSON）
            [
                {
                    "question": "你的问题",
                    "answer": "答案",
                    "according": "原文依据"
                }
            ]
            """;

    private static final String GenerationSimpleDocMultiHop_ZH = """
            #角色：你是一个高质量问答数据集生成助手，用于构建检索增强生成（RAG）系统的问答测试数据。
            # 任务：请根据给定文档生成1-2条多跳问答对并给出文档中对应的依据。
            # 定义
            多跳问题指：
                - 需要整合文档中**两个及以上独立信息点**才能回答
                - 需要简单推理/关联/组合，不能直接从单一句子找到完整答案
                - 禁止纯单跳、纯查找类问题
            # 要求
                - 问题必须是自然语言提问（像真实用户会问的）
                - 答案必须基于文档内容推理得出，禁止编造
                - 依据需标注所有用到的原文信息点
            # 输入 
            文档名：%s
            文档内容：%s
            
            # 输出格式（严格JSON）
            [
                {
                    "question": "你的问题",
                    "answer": "答案",
                    "according": "原文依据"
                }
            ]
            """;

    private static final String GenerationCrossDoc_ZH = """
            #角色：你是一个高质量跨文档问答数据集生成助手，用于构建检索增强生成（RAG）系统的跨文档测试数据。
            # 任务：根据**多篇相关文档**，生成3-4条**跨文档多跳问答对**并给出所有依据。
            # 定义
            跨文档多跳问题指：
                - 答案必须**同时依赖两篇及以上独立文档**才能得出
                - 需要整合不同文档中的**多个信息点**，进行推理/关联/计算/对比
                - 不能从任意单篇文档直接找到完整答案
                - 禁止单文档问题、纯查找类问题
            # 要求
                - 问题必须是自然语言提问（像真实用户会问的）
                - 答案必须严格基于所有文档内容推理，禁止编造
                - 依据必须**分文档标注**所有用到的原文信息
            # 输入
            相关文档内容：%s
            
            # 输出格式（严格JSON，不许额外文字）
            [
                {
                    "question": "跨文档问题",
                    "answer": "跨文档推理答案",
                    "according": "文档1依据：xxx；文档2依据：xxx"
                }
            ]
            """;

    private static final String GenerationSummaryLevel_ZH = """
            根据以下数据集的描述：
            %s
            请识别出 5 个会使用该数据集的潜在用户。对于每个用户，列出他们会用该数据集执行的 3 个任务。然后，针对每个（用户，任务）组合，生成 3 个需要对整个数据集有高层次理解才能回答的问题。
            请按以下结构输出结果：
            - 用户 1：[用户描述]
                - 任务 1：[任务描述]
                    - 问题 1：
                    - 问题 2：
                    - 问题 3：
                - 任务 2：[任务描述]
                ...
                - 任务 3：[任务描述]
            - 用户 2：[用户描述]
            ...
            - 用户 5：[用户描述]
            严格要求：
                1. question 必须自包含，不能出现"该""其""此"等指代词，必须把具体对象写清楚。例如："该岗位的试用期多长？" 应写成 "XX公司运维工程师岗位的试用期多长？"
                2. answer 和 according 需基于数据集实际内容生成，不能编造。
                3. 禁止询问在文档中一下可以找到答案的具体问题。例如：询问具体时间、人数、金额等等。
            输出：
            {
                "users": [
                    {
                        "description": "用户描述",
                        "tasks": [
                            {
                                "fileId":"",
                                "description": "任务描述",
                                "questions": [
                                    {
                                        "question": "问题内容",
                                         "answer": "参考答案",
                                        "according": "回答依据"
                                    }
                                ]
                            }
                        ]
                    }
                ]
            }
            
            
            """;

    private static final String RetrievalPlanPrompt_ZH = """
            # 角色：你是一个问题分析智能体
            # 任务：分析用户问题，判断问题类型、抽取关键词并归纳主题
            
            ## 详细任务
            1. 判断问题类型（queryType）
            2. 抽取关键词（keywords）
            3. 提取相关主题（topics），用于主题检索
            
            ## 一、问题类型定义（queryType）
            
            ### 1. MULTI_HOP（多跳推理问题）
            需要经过多步关系推理才能回答的问题，涉及多个实体之间的关联跳转。
            特征：
            - 需要先找到A，再通过A找到B，再通过B找到C
            - 问题中存在隐含的中间推理步骤，必须跨多个实体才能回答，不能通过单次检索直接回答
            示例：
            - 张三所在部门参与的涉密项目有哪些
            - 北京大学陈老师的学生发表过哪些论文
            
            ### 2. GLOBAL_ABSTRACT（全局抽象问题）
            总结归纳类问题：用户需要宏观总结、风险归纳、整体概括等高层级认知的问题。
            示例：
                - 2025年有哪些项目风险
                - 当前工作的主要问题有哪些
            
            ### 3. SPECIFIC_DETAIL（具体细节问题）
            询问明确事实、属性、联系方式、时间、原因、具体要求等可直接定位的具体信息。
            特征：
            - 有明确的查询目标（某个人、某个机构、某个时间点、某个具体事件）
            - 答案通常是具体的描述性内容，可在文档中直接找到
            - **关键区分**：即使问题包含"为什么""什么原因"，只要问的是某个具体事件的原因/背景/细节，仍属于SPECIFIC_DETAIL，不需要多跳推理
            示例：
            - 张三的电话是多少
            - XX项目负责人是谁
            - 计算机学院的办公室在哪里
            - 关于XX考核的要求是什么
            
            ## 二、关键词抽取规则（keywords）
             1. keywords字段用于保存：人名、地名、机构名、项目名、系统名、专业术语等
            
            ## 三、主题提取规则（topics）
             1. 主题是对问题领域的高层概括，用于主题检索
            示例：
            问题："2025年的项目风险有哪些"
            topics：["项目风险","项目管理"]
            
            ### 按诊断类型调整策略：
            
            #### WRONG_RELATION_PATH（多跳关系选择错误）：
            - 保持 queryType 为 MULTI_HOP 不变
            - 参考 suggestionDetail 中的建议关系调整关键词和 topics
            
            #### INSUFFICIENT_EXPANSION（多跳扩展不充分）：
            - 保持 queryType 为 MULTI_HOP 不变
            - 根据 suggestionDetail 补充关键词和 topics
            
            #### WRONG_TOPICS（主题不合适）：
            - 保持 queryType 为 GLOBAL_ABSTRACT 不变
            - 考虑是否将 topics 替换为 suggestionDetail 中建议的主题
            
            #### NEED_QUESTION_REWRITE（需要问题改写）：
            - 保持 queryType 不变
            - 根据 suggestionDetail 中的改写问题重新抽取关键词和 topics
            
            #### WRONG_QUERY_TYPE（问题类型定义错误）：
            - 将 queryType 改为 suggestionDetail 中建议的正确类型
            - 重新抽取关键词和 topics
            
            ## 反思与改进规则（非常重要）
                1. 哪些工具已经调用过
                2. 哪些检索关键词已经尝试过
                3. 哪些检索方向已经失败
                4. 评估智能体指出的问题是什么
            
            # 输入
            用户问题：
            %s
            历史执行记录与诊断结果：
            %s
            
            # 输出
            必须严格遵循以下格式（单行JSON，不换行）：
            {"queryType":"枚举值","keywords":["关键词1","关键词2"],"topics":["主题1","主题2"]}
            
            输出规则：
            1. 必须是单行有效 JSON，不允许换行
            2. queryType 只能是 MULTI_HOP、GLOBAL_ABSTRACT、SPECIFIC_DETAIL
            3. 无内容时数组必须为空 []，不能为 null
            4. 禁止添加任何解释、说明、额外文字
            
            示例1（多跳推理）：
            {"queryType":"MULTI_HOP","keywords":["张三","涉密项目"],"topics":["人员组织关系","项目管理"]}
            
            示例2（全局抽象）：
            {"queryType":"GLOBAL_ABSTRACT","keywords":["项目风险","2025年"],"topics":["项目风险管理","风险评估","项目安全"]}
            
            示例3（具体细节）：
            {"queryType":"SPECIFIC_DETAIL","keywords":["张三","电话"],"topics":["人员联系方式","教职工信息"]}
            
            """;

    private static final String SystemCOT_ZH = """
            你是一个"工具调用智能体"，**只负责选择并调用检索工具**，不生成最终回答。
            
            你的唯一职责：
            1. 根据输入的结构化问题分析结果，判断需要调用哪些工具
            2. 调用工具获取知识
            
            **重要：你只需要调用工具，工具调用完成后直接输出纯文本（如"检索完成"），不要生成任何回答文本。最终回答将由后续阶段根据检索结果单独生成。
            你只能使用系统提供给你的工具。严禁编造或调用任何不存在的工具名称（如 stop、finish、end、done、stop_tool_calling 等）。如果找不到合适的工具调用，就直接输出文本结束本轮。**
            
            【按问题类型的工具选择规则】
            
            1. SPECIFIC_DETAIL（具体细节问题）：
               检索策略：
               - 直接使用 searchFileByQuery(query=原始问题)，检索与问题语义最匹配的文档内容或使用searchEntityByName(names=关键实体列表)检索相关节点信息
               - 如果文档检索无结果，尝试用 topics 进行主题检索兜底
            
            2. MULTI_HOP（多跳推理问题）：
               可用工具：getEntityTriples（返回实体属性 + 最多10条三元组）
               三元组格式：(实体名) -[关系类型]-> (邻居实体名)
            
               多跳推理步骤：
            
               第一步（定位起始实体）：
               - 使用 getEntityTriples(names=关键词)，获取实体属性和关联三元组
               - 仔细阅读返回的每一条三元组，理解实体有哪些关联关系
            
               第二步（语义匹配三元组）：
               - 根据问题语义，从三元组列表中找到最相关的那条
               - 例如：问题问"张三所在部门参与的涉密项目"
                 返回三元组：1. (张三) -[属于]-> (计算机学院)  2. (张三) -[指导]-> (李四)
                 选择第1条（"属于"表示归属关系）
            
               第三步（从三元组提取邻居名，继续扩展）：
               - 提取邻居实体名（如"计算机学院"）
               - 用邻居名再次调用 getEntityTriples(names=["计算机学院"])
               - 从返回的三元组中继续匹配与问题相关的（如"参与项目"关系）
               - 可重复此步骤直到获得足够信息
            
               辅助工具：
               - searchFileByQuery：当图谱信息不足时，用用户的原始问题全文检索文档补充
            
            3. GLOBAL_ABSTRACT（全局抽象问题）：
               检索策略：
               - 优先使用 searchFileByTopic，参数用 topics（相关主题）+ query（原始问题）
               - 可使用 searchFileByQuery 辅助进行全文语义检索作为补充
            
            【工具调用约束】
            - 每轮至少选用一个检索工具
            - 当调用工具时，必须优先使用"问题分析智能体结果"中的内容作为参数来源。
            - 禁止重复调用"相同工具 + 相同语义参数"
            - 若某工具已对某语义问题返回结果，则必须：
              1. 更换检索维度（实体 → 主题 → query）
              2. 或直接终止该工具调用
            - 禁止通过改写词序、增删无意义词重复查询
            - 每次工具调用必须引入新的信息增量目标
            - 当关键词检索不到结果时，尝试使用 topics 进行主题检索
            - 多跳问题：使用 getEntityTriples 获取三元组，从三元组中提取邻居实体名，用邻居名再次调用 getEntityTriples 继续扩展
            
            【工具调用次数限制】（严格遵守，防止上下文溢出）
            - 最多进行 4 次工具调用，达到上限后必须直接回复纯文本，不调用任何工具。
            - 多跳问题：getEntityTriples 最多调用 3 次，searchFileByQuery 最多 1 次作为补充。
            - 细节问题：searchFileByQuery/searchFileByTopic/searchEntityByName 合计最多 2 次。
            - 第 4 次调用后，无论结果如何都不再调用任何工具，直接回复文本。
            
            你必须优先使用工具。工具调用全部完成后，直接回复纯文本（如"检索完成"），禁止输出任何回答内容，禁止编造任何工具名。
            """;

    private static final String RetrievalPrompt_ZH = """
            # 角色：你是一个"检索决策与工具调用智能体"。
            
            # 任务：
                1. 你需要根据"问题分析智能体"的结果去选择检索工具，将问题分析智能体的相关结果作为检索工具的参数。
                2. 自主判断应该调用哪些检索工具。
                3. 考虑使用哪个工具更可能获得问题/子问题的答案。
                4. **你的唯一任务是调用工具完成检索，不需要生成最终回答。** 最终回答将由后续阶段单独处理。
            
            # 问题分析智能体结果
            用户问题：%s
            问题类型：%s
            关键词：%s
            相关主题：%s
            
            请根据以上分析结果，自主选择检索工具。只调用工具，不要输出任何回答文本。
            """;

    private static final String AutonomousRetrievalPrompt_ZH = """
            # 角色：你是一个"检索决策与工具调用智能体"。

            # 任务：
                1. 根据用户问题，自主选择并调用合适的检索工具。
                2. 考虑使用哪个工具更可能获得问题的答案。
                3. **你的唯一任务是调用工具完成检索，不需要生成最终回答。** 最终回答将由后续阶段单独处理。

            # 用户问题
            %s

            请根据用户问题自主选择检索工具。只调用工具，不要输出任何回答文本。
            """;

    private static final String AgentEvaluation_ZH = """
            # 角色：答案评估专家，判断初始答案能否回答用户问题，失败时给出结构化诊断。
            
            ## 核心任务：
            1. 判断答案是否能回答用户问题（PASS/FAIL）
            2. FAIL时必须给出 diagnosis 诊断信息，指导下一轮调整策略
            
            ## 诊断规则：
            
            ### 1. 多跳问题（使用 getEntityTriples 工具）
            - 关系选择与问题语义不匹配 → WRONG_RELATION_PATH
            - 扩展跳数不够 → INSUFFICIENT_EXPANSION
            
            ### 2. 全局抽象类问题（使用 searchFileByTopic 工具）
            - 主题不合适 → WRONG_TOPICS
            - 需要更精准表述 → NEED_QUESTION_REWRITE
            
            ### 3. 问题类型错误
            - 问题分析智能体定义的类型与实际需求不匹配 → WRONG_QUERY_TYPE
            
            suggestionDetail 根据 problemType 填写对应内容（如关系语义描述、建议主题、改写问题、正确类型等）。
            nextRoundStrategy 必须给出下一轮具体可执行的策略。
            
            # 输入：
            用户问题：%s
            当前轮次的问题分析结果：%s
            初始答案：%s
            答案依据（工具检索结果）：
            %s
            
            # 输出JSON（单行，无额外文字）：
            {"success":"PASS或FAIL","reason":"理由","diagnosis":{"problemType":"WRONG_RELATION_PATH|INSUFFICIENT_EXPANSION|WRONG_TOPICS|NEED_QUESTION_REWRITE|WRONG_QUERY_TYPE","detail":"诊断说明","suggestionDetail":"修改建议","nextRoundStrategy":"策略建议"}}
            
            示例：
            PASS：{"success":"PASS","reason":"答案准确完整回答了问题","diagnosis":null}
            
            WRONG_RELATION_PATH：{"success":"FAIL","reason":"问题问部门参与的涉密项目，但选了'指导学生'关系，与问题无关","diagnosis":{"problemType":"WRONG_RELATION_PATH","detail":"选了'指导学生'，但核心是部门→涉密项目，应从实际关系中语义匹配归属关系","suggestionDetail":"建议关系：语义表示归属/所属的关系、语义表示参与项目的关系","nextRoundStrategy":"更换语义方向：先获取部门，再从部门关系中匹配参与项目的关系"}}
            
            WRONG_TOPICS：{"success":"FAIL","reason":"主题'项目风险管理'检索无结果，过于狭窄","diagnosis":{"problemType":"WRONG_TOPICS","detail":"当前主题无匹配","suggestionDetail":"建议主题：项目安全、年度报告、风险排查","nextRoundStrategy":"更换主题：用['项目安全','年度报告']检索，辅以searchFileByQuery"}}
            
            WRONG_QUERY_TYPE：{"success":"FAIL","reason":"问题分析智能体定义为MULTI_HOP，但张三的电话是具体细节问题，类型定义错误","diagnosis":{"problemType":"WRONG_QUERY_TYPE","detail":"具体事实查询不需要多跳推理，问题分析智能体的queryType定义错误","suggestionDetail":"正确类型：SPECIFIC_DETAIL","nextRoundStrategy":"重新分类为SPECIFIC_DETAIL，用searchFileByQuery检索"}}
            
            INSUFFICIENT_EXPANSION：{"success":"FAIL","reason":"只扩展到学生层级，未获取论文信息","diagnosis":{"problemType":"INSUFFICIENT_EXPANSION","detail":"完成教师→学生一跳，还需学生→论文第二跳","suggestionDetail":"建议关系：语义表示发表/撰写的关系","nextRoundStrategy":"继续扩展：对每个学生节点，从实际关系中匹配表示发表的关系"}}
            """;

    private static final String BaselinePrompt_ZH = """
            #任务：根据检索到的文档内容回答用户问题
            
            #要求
            1. 回答必须**完全基于检索到的文档内容**，不允许编造、扩展或使用外部知识。
            2. 如果文档中没有相关答案，请直接回复："根据提供的文档，未找到相关信息。"
            
            #输入：
            用户问题：%s
            相关文档：%s
            """;

    private static final String IntentAnswerPrompt_ZH = """
            # 角色：你是一个智能检索问答助手，擅长根据多源检索结果综合分析并精准且全面的回答用户问题。
            
            ## 核心能力
            1. **信息整合**：能够将来自不同检索工具（语义搜索、主题检索、知识图谱查询）的结果进行综合分析，提取并分析共同信息、互补信息。
            2. **去伪存真**：识别检索结果中的冗余、矛盾信息，优先采信与问题最相关、来源最可靠的内容。
            3. **结构化表达**：根据问题类型选择合适的回答格式，如列表、分点陈述、表格对比等。
            
            ## 回答原则
            1. **完整全面**：综合所有检索结果，不遗漏任何与问题相关的信息点。
            2. **条理清晰**：回答要有逻辑结构，先给出核心结论，再展开详细的细节支撑。
            3. **信息归纳**：对于重复出现的相同信息，应进行合并归纳；对于互补信息，应进行补充整合。
            4. **要素覆盖**：回答应尽量覆盖用户问题涉及的主体、时间、地点、原因、过程、结果、条件、影响等关键要素。
            5. **复杂问题处理**：对于复杂问题或多跳问题，应先识别问题中的多个子问题，再分别整合相关证据，最后给出综合结论。
            
            
            ## 输入
            用户问题：%s
            
            检索结果：
            %s
            
            """;

    private static final String AssessUserPrompt_ZH = """
            # 任务：
            你的任务是依据四项标准对下方回答进行评估。每项标准打分区间为 1 至 10 分，打分需严格遵循详细评分细则。
            # 注意：
            打分里有必须引用答案原文内容进行作证，禁止空泛笼统的描述，所有解释要结合实际文本。
            # 指标介绍
            - 内容全面性 contentComprehensiveness
            该回答提供的细节是否完整覆盖问题的全部层面与细分要点？
            - 指导性 empowerment
            回答能否有效帮助读者理解相关主题，并支撑读者做出客观、合理的判断？
            - 视角多样性 diversity
            回答能否有效帮助读者理解相关主题，并支撑读者做出客观、合理的判断？
            - 整体质量 overall
            结合前面三项项指标综合评估整体表现，兼顾内容质量与答案对原问题的实用价值。
            
            ## 打分分级标准
            1-2分：极差，该项缺陷突出，存在严重内容问题；
            3-4分：偏弱，多项关键内容缺失，问题明显；
            5-6分：中等，刚好满足基础作答要求，存在少量瑕疵；
            7-8分：优良，整体表现出色，仅存在细微不足；
            9-10分：优秀，该维度表现极佳，无明显缺陷。
            
            # 输入
            待评估问题：%s
            待评估答案：%s
            正确答案：%s
            
            请按照上述四项标准完成打分评估，并结合原文为每项标准撰写详细说明。
            输出格式严格遵循以下 JSON 结构：
            {
            "contentComprehensiveness": {
            "score": "[1-10 之间的数字]",
            "explanation": "[在此填写说明文字]"
            },
            "empowerment": {
            "score": "[1-10 之间的数字]",
            "explanation": "[在此填写说明文字]"
            },
            "diversity": {
            "score": "[1-10 之间的数字]",
            "explanation": "[在此填写说明文字]"
            },
            "overall": {
            "score": "[1-10 之间的数字]",
            "explanation": "[结合前三项标准，总结该回答综合表现优劣]"
            }
            }
            """;

    // ================================================================
    //  ====================  English Prompts  ========================
    // ================================================================

    private static final String FileNodeExtraction_EN = """
            # Role: You are a Knowledge Graph Construction Assistant.
            
            ## Basic Information
            - Language: English
            - Description: You excel at extracting document metadata and summaries from document names and content.
            - Task: Extract document node information from the following document name and content.
            
            ## Objectives
            1. Output the entire document as a single entity node (only one entity).
            2. Use the document name as entityName (e.g., "Annual Security Report 2024").
            3. entityDescription should be a content summary in natural language, covering the core business, main items, or key information of the document, within 150 words.
               **Note**: entityDescription focuses on "what the document is about and what key information it contains".
            4. entityInfo stores document metadata in the format "key1:value1; key2:value2; ...", including the following fields (only extract those explicitly found in the text):
               - Publication Date: the document's release date or signature date
               - Issuing Organization: the publishing unit or issuing authority
               - CC Units: carbon-copied or copied units
               - Document Number: the document's reference number
               - Other key attributes
            5. Only extract information explicitly found in the text. Do not fabricate.
            6. Strong evidence anchoring, no duplication or speculation.
            
            ## Input Format
            Document Name: "%s"
            Document Content: "%s"
            
            ## Output Format
            Output complete JSON with an array named "entities" (only one element):
            {
              "entities": [
                {
                  "entityName": "Document Name",
                  "entityDescription": "Document summary, within 50 words",
                  "entityInfo": "Publication Date:xxxx; Issuing Organization:xxxx; CC Units:xxxx"
                }
              ]
            }
            
            Important Notes:
            1. Output complete JSON object only, no extra text or comments.
            2. The entities array must contain exactly one document node.
            3. Separate entityInfo fields with semicolons in "fieldName:fieldValue" format.
            4. If an attribute is empty, omit that field from entityInfo.
            5. If a field is empty, use "".
            ######################
            - Example -
            ######################
            Example 1:
            Document Name: Annual Campus Security Report 2024
            Document Content: The Annual Campus Security Report 2024 outlines the security measures implemented across all campus locations during the 2024 academic year. Key initiatives include the installation of 50 new surveillance cameras, hiring of 10 additional security personnel, and implementation of a new digital check-in system for visitors. The report was issued by the Campus Security Office on December 15, 2024. Copies were distributed to the President's Office, Facilities Management, and Student Affairs Department.
            ######################
            Output:
            {
              "entities": [
                {
                  "entityName": "Annual Campus Security Report 2024",
                  "entityDescription": "Summarizes campus security measures implemented during 2024, including new surveillance camera installations, additional security personnel hiring, and a new digital visitor check-in system across all campus locations.",
                  "entityInfo": "Publication Date:December 15, 2024; Issuing Organization:Campus Security Office; CC Units:President's Office, Facilities Management, Student Affairs Department"
                }
              ]
            }
            
            """;

    private static final String ContentEntityExtraction_EN = """
            # Role: You are a Knowledge Graph Construction Assistant.
            
            ## Basic Information
            - Language: English
            - Description: You excel at comprehensively and meticulously extracting all entities from document content, describing each entity's core information in natural language.
            - Task: Extract **as comprehensively as possible** all entities from the following document content, providing a natural language description for each entity.
            
            ## Entity Types
            Choose the most appropriate type from [Person, Technology, Task, Organization, Location, Event, Time, Document, Concept].
            
            ## Objectives
            1. **Identify all named entities from the document content as exhaustively as possible**, including but not limited to: person names, organization names, location names, event names, time points, file names, regulation names, project names, facility names, phone numbers, document numbers, etc.
            2. **Do not omit any entity that explicitly appears in the document**. Even if an entity seems less important, as long as it is a named entity explicitly mentioned in the document, it should be extracted.
            3. Generate a concise natural language description (entityDescription) for each entity, containing its core attributes, roles, context, and other information as it appears in the document.
            4. entityInfo stores structured attribute information for the entity in the format "key1:value1; key2:value2; ...", including the following fields (only extract those explicitly found in the text):
               - For Person: position, contact info, department, etc.
               - For Organization: address, contact info, parent organization, etc.
               - For Location: area, functional purpose, etc.
               - For Event: occurrence time, participants, etc.
               - For Technology: technical domain, application scenarios, etc.
               - For Task: responsible department, time requirements, etc.
               - Other key attributes
            5. Output JSON, each record containing:
               - entityName: entity name
               - entityType: entity type
               - entityDescription: natural language description of the entity (summarize core information in one sentence)
               - entityInfo: structured attribute information for the entity
            6. Only extract entities and attributes explicitly found in the text. Do not fabricate.
            7. Strong evidence anchoring, no duplication or speculation.
            8. Each entity appears only once. Do not create multiple records for different attributes of the same entity.
            
            ## Input Format
            Document Content: "%s"
            
            ## Output Format
            Extract entities into complete JSON format with an array named "entities":
            {
              "entities": [
                {
                  "entityName": "Entity Name",
                  "entityType": "Entity Type",
                  "entityDescription": "Natural language description of the entity",
                  "entityInfo": "Position:Principal; Organization:Example University; Phone:123-456-7890"
                }
              ]
            }
            ######################
            - Example -
            ######################
            Example 1:
            Document Content: The 2025 Spring Commencement Ceremony of Example University was held at the Main Auditorium on River Campus. President John Smith presided over the ceremony. Attendees included Computer Science Dean Alice Wang, Student Affairs Director Bob Chen, and representatives from various colleges.
            ######################
            Output:
            {
              "entities": [
                {"entityName": "2025 Spring Commencement Ceremony", "entityType": "Event", "entityDescription": "The 2025 Spring Commencement Ceremony of Example University was held at the Main Auditorium on River Campus, presided over by President John Smith, with Computer Science Dean Alice Wang, Student Affairs Director Bob Chen, and various college representatives in attendance.", "entityInfo": "Venue:Main Auditorium, River Campus; Presider:John Smith"},
                {"entityName": "Main Auditorium", "entityType": "Location", "entityDescription": "The main auditorium on Example University's River Campus, venue for the 2025 Spring Commencement Ceremony.", "entityInfo": "Campus:River Campus; Purpose:Hosting major events"},
                {"entityName": "John Smith", "entityType": "Person", "entityDescription": "President of Example University, presided over the 2025 Spring Commencement Ceremony.", "entityInfo": "Position:President; Organization:Example University"},
                {"entityName": "Alice Wang", "entityType": "Person", "entityDescription": "Dean of the Computer Science department at Example University, attended the 2025 Spring Commencement Ceremony.", "entityInfo": "Position:Computer Science Dean; Organization:Example University"},
                {"entityName": "Bob Chen", "entityType": "Person", "entityDescription": "Student Affairs Director at Example University, attended the 2025 Spring Commencement Ceremony.", "entityInfo": "Position:Student Affairs Director; Organization:Example University"},
                {"entityName": "Example University", "entityType": "Organization", "entityDescription": "A university with a River Campus, hosted the 2025 Spring Commencement Ceremony.", "entityInfo": "Campus:River Campus"},
                {"entityName": "Computer Science Department", "entityType": "Organization", "entityDescription": "A department under Example University, headed by Dean Alice Wang.", "entityInfo": "Parent Organization:Example University; Dean:Alice Wang"}
              ]
            }
            
            Important Notes:
            1. Output complete JSON object only, no extra text or comments.
            2. Maintain field order consistent with the example.
            3. entityDescription should be a complete, fluent natural language description.
            4. Separate entityInfo fields with semicolons in "fieldName:fieldValue" format. If an attribute is empty, omit that field from entityInfo.
            5. If a field is empty, use "".
            6. Extract all entities as comprehensively as possible. More is better than less.
            """;

    private static final String EntitiesRelationExtraction_EN = """
            # Role: You are a Knowledge Graph Construction Assistant.
            
            ## Basic Information
            - Language: English
            - Description: You excel at extracting relationships between entities within documents based on document content.
            - Task: Extract relationships between content entities within the document based on the document content.
            
            ## Relationship Extraction Principles
            1. Identify all explicitly related entity pairs (source entity → target entity) from the provided document content and all internal document entities.
            2. Both source and target entities **must come from the internal document entity list**. Use entity names, not IDs.
            3. Output JSON. Each relationship must include:
               - sourceName: source entity name
               - targetName: target entity name
               - sourceType: source entity type
               - targetType: target entity type
               - description: relationship description (describe the specific association between the two entities in natural language, e.g., "John Smith serves as President of Example University")
               - keywords: generalized keywords derived from the source and target entity names, joined by "+". Generalization rule: replace specific proper nouns with category words or hypernyms that capture their essence, preserving the semantic searchability of the relationship. For example, source "John Smith" generalizes to "president", target "Example University" generalizes to "university", so keywords would be "president+university". Similarly, "Main Auditorium" generalizes to "auditorium", "Computer Science Department" generalizes to "department".
            4. Do not generate new entities. Do not modify existing entity names.
            5. Do not output semantically duplicate relationships.
            6. Relationships must have **explicit textual evidence** in the document content. No speculation or generalization.
            7. Both sourceName and targetName must be non-empty strings and must be two different entities. Invalid relationships with only one-side entities are prohibited.
            8. If the document has only one entity or no clear associations between entities, return an empty array {"relations": []}.
            
            ## Input Format
            Document Content: "%s"
            Internal Document Entities (JSON array, each object containing name, type, description, info): "%s"
            
            
            ## Output Format
            Extract relationships into complete JSON format with an array named "relations":
            {
              "relations": [
                {
                  "sourceName": "Source Entity Name",
                  "targetName": "Target Entity Name",
                  "sourceType": "Source Entity Type",
                  "targetType": "Target Entity Type",
                  "description": "Natural language description of the relationship",
                  "keywords": "generalizedSourceTerm+generalizedTargetTerm"
                }
              ]
            }
            
            Important Notes:
            1. Output must be a complete JSON object, not JSONL or other extra text.
            2. Maintain field order strictly consistent with the example.
            3. If an attribute is empty, use an empty string "".
            4. Each item in the array is an object.
            5. Do not output any extra explanations, hints, or text.
            6. Use entity names for sourceName and targetName, not IDs.
            
            ######################
            - Example -
            ######################
            Example 1:
            Input:
            Document Content: The 2025 Spring Commencement Ceremony of Example University was held at the Main Auditorium on River Campus. President John Smith presided over the ceremony. Attendees included Computer Science Dean Alice Wang, Student Affairs Director Bob Chen, and representatives from various colleges.
            Internal Document Entities: [
              {"name": "2025 Spring Commencement Ceremony", "type": "Event", "description": "The 2025 Spring Commencement Ceremony was held at the Main Auditorium on River Campus", "info": ""},
              {"name": "Main Auditorium", "type": "Location", "description": "The main auditorium on River Campus", "info": ""},
              {"name": "John Smith", "type": "Person", "description": "President of Example University", "info": ""},
              {"name": "Alice Wang", "type": "Person", "description": "Dean of Computer Science", "info": ""},
              {"name": "Bob Chen", "type": "Person", "description": "Student Affairs Director", "info": ""},
              {"name": "Example University", "type": "Organization", "description": "An information technology university", "info": ""},
              {"name": "Computer Science Department", "type": "Organization", "description": "A department under Example University", "info": ""}
            ]
            ######################
            Output:
            {
              "relations": [
                {"sourceName": "2025 Spring Commencement Ceremony", "targetName": "Main Auditorium", "sourceType": "Event", "targetType": "Location", "description": "The 2025 Spring Commencement Ceremony was held at the Main Auditorium on River Campus", "keywords": "ceremony+auditorium"},
                {"sourceName": "John Smith", "targetName": "2025 Spring Commencement Ceremony", "sourceType": "Person", "targetType": "Event", "description": "President John Smith presided over the 2025 Spring Commencement Ceremony", "keywords": "president+ceremony"},
                {"sourceName": "John Smith", "targetName": "Example University", "sourceType": "Person", "targetType": "Organization", "description": "John Smith serves as President of Example University", "keywords": "president+university"},
                {"sourceName": "Alice Wang", "targetName": "Computer Science Department", "sourceType": "Person", "targetType": "Organization", "description": "Alice Wang serves as Dean of the Computer Science Department", "keywords": "dean+department"},
                {"sourceName": "Computer Science Department", "targetName": "Example University", "sourceType": "Organization", "targetType": "Organization", "description": "The Computer Science Department is a subordinate department of Example University", "keywords": "department+university"}
              ]
            }
            
            """;

    private static final String FileTopicExtraction_EN = """
            # Role: You are an expert at distilling article topics.
            
            ## Basic Information
            - Language: English
            - Description: You excel at summarizing document topics based on document names and content.
            - Task: Summarize the document topic based on the document name and content.
            - Note: The topic should be relevant to the content. Avoid overly generic terms like time, attachment, notice, arrangement, announcement, rules, management, announcement. Do not include organization or institution names.
            - Note: The topic should be a single word or phrase that best captures the document content.
            
            ## Topic Extraction Principles
            1. Summarize the document topic from the provided document name and content.
            2. The topic should best reflect the document content, and must not exceed 30 characters.
            
            ## Input Format
            Document Name: "%s"
            Document Content: "%s"
            
            
            ## Output Requirements
            1. Output only one final topic (word or phrase).
            2. Do not output any explanation, reason, analysis, or extra content.
            3. Do not repeat the input document name or content.
            4. Do not output meta-text like "Input:" or "Output:".
            5. Output must not contain line breaks or multiple sentences.
            6. Output length must not exceed 30 characters.
            
            ######################
            - Example -
            ######################
            Example 1:
            Input:
            Document Name: Campus Holiday Duty Schedule 2025.doc
            Document Content: Campus Holiday Duty Schedule 2025. The schedule outlines staff assignments for the holiday period from December 24 to January 2. Key personnel include Building Supervisor Jane Doe (ext. 1234), Security Lead Mike Johnson (ext. 5678), and Facilities Manager Sarah Lee (ext. 9012). Emergency contact: Campus Security at 555-0100. All staff must complete their assigned shifts and report any incidents to the Duty Office.
            Output: Holiday Duty Schedule
            """;

    private static final String FileTopicSummaryExtraction_EN = """
            # Role: You are an expert at distilling article topics.
            
            ## Basic Information
            - Language: English
            - Description: You excel at summarizing the overall document topic based on the document name and multiple chunk topics.
            - Task: Generate the final document topic based on the document name and the topics of each document chunk.
            - Note: Prioritize the document name, treating chunk topics as supplementary.
            
            ## Topic Refinement Rules
            1. If there are **repeated or highly similar terms** across multiple chunk topics, prioritize using that term as the final topic.
            2. If no obvious repeated topic exists, then synthesize all chunk topics.
            3. The document topic should best summarize the overall content.
            4. The document topic must not exceed 30 characters.
            5. Do not repeat input content. Do not explain reasoning.
            
            ## Input Format
            Document Name: "%s"
            Chunk Topics:
            %s
            
            ## Output Requirements
            - Output only one word or phrase as the final document topic.
            - Do not include any descriptions, punctuation, or example explanations.
            - Must not exceed 30 characters.
            
            
            ######################
            - Example -
            ######################
            Example 1:
            Input:
            Document Name: Campus Holiday Duty Schedule 2025.doc
            Chunk Topics:
                - Staff Duty Assignments
                - Campus Security Shifts
                - Emergency Contacts and Responsibilities
                - Campus Duty Schedules
            Output: Holiday Duty Schedule
            
            Example 2:
            Input:
            Document Name: 2024 Graduate Admission Guidelines.doc
            Chunk Topics:
            - Graduate Admission Process
            - Admission Requirements
            - Graduate Enrollment Arrangements
            Output: Graduate Admissions
            """;

    private static final String TopicDescriptionExtraction_EN = """
            # Role: You are a professional information synthesis expert, skilled at extracting common information from multiple documents and producing an overall summary.
            
            [Task Description]
            Given multiple document information entries under the same topic cluster (document topic, document name, document content), synthesize all document information to generate the cluster name and an overall description of the documents within the cluster.
            1. Synthesize all documents under this topic cluster to summarize the core theme, main content, and common focus areas.
            2. Preserve common viewpoints and important details.
            3. Remove irrelevant details.
            4. The cluster name should be concise and closely match the core content, preferably 4-10 words.
            5. The overall description should be within 100 words, fluent and appropriate for the domain context.
            6. Output strictly in the specified format without additional explanations or irrelevant remarks.
            
            ## Input Data
            Each document contains:
            - Topic
            - Document Name (title)
            - Document Content (content)
            
            Documents to summarize:
            %s
            
            Output:
            Cluster Name:
            Description:
            ##########################
            Example 1:
            Input:
            Topic: Graduate Clearance Procedures, Document Name: University of Example 2025 Graduate Clearance Notice, Content: Specifies the processes for graduate library book returns, dormitory check-out, and property handover, with dedicated staff assigned to expedite clearance procedures.
            Topic: Graduate Settlement Management, Document Name: University of Example 2025 Graduate Fee Settlement Notice, Content: Reviews tuition, accommodation, and related fees, completes financial settlement and clearance approval procedures to ensure smooth graduate departure.
            
            Standard Output:
            [Cluster Name] Graduate Clearance Process
            [Description] Outlines the University of Example's 2025 graduate procedures including library book returns, dormitory check-out, property handover, and fee settlement processes to facilitate smooth student departure.
            """;

    private static final String DescriptionSummary_EN = """
            # Role: You are a professional text editor, skilled at removing redundancy and polishing concatenated descriptions.
            
            [Task Description]
            Below is a text composed by concatenating multiple descriptions. Please organize and optimize it.
            Requirements:
            1. Merge identical or similar expressions, remove redundancy, but do not lose any key information points.
            2. Preserve all key information points (such as location, purpose, affiliated organization, time, personnel, specific details, etc.). Do not omit anything.
            3. Output fluent and natural English descriptions. Do not deliberately compress word count. Completeness of information takes priority.
            4. Do not use separators like +, |. Use natural language to connect information points.
            
            ##########################
            Example:
            Input:
            Building A is a teaching building at Example University + Building A at Example University, the teaching building for the School of Engineering | One of the teaching buildings at Example University, primarily used by the School of Engineering, located on Main Campus
            
            Output:
            Building A is a teaching building at Example University, located on Main Campus, primarily used by the School of Engineering and serving as both a teaching and office facility for the school.
            
            ## Original Description
            %s
            
            ## Organized Description
            """;

    private static final String EntityGleaning_EN = """
            # Role: You are a Knowledge Graph Construction Assistant.
            
            ## Basic Information
            - Language: English
            - Description: You excel at supplementing the discovery of missed entities from document content.
            - Task: Given the document content and the already extracted entity list, identify any important entities that may have been missed.
            
            ## Principles
            1. Carefully read the document content and check sentence by sentence whether the extracted entity list is complete.
            2. **Focus on**: person names, organization names, location names, event names, time points, file/attachment names, regulation names, project names, phone numbers, document numbers, etc., that explicitly appear in the document but are not in the list.
            3. Only supplement entities that explicitly appear in the document and have independent informational value. For example: every person name, every department, every location listed should be a separate entity.
            4. Do not duplicate already extracted entities.
            5. If there are no missed entities, return an empty array.
            
            ## Input Format
            Document Content: "%s"
            Extracted Entity List: %s
            
            ## Output Format
            Output in complete JSON format with an array named "entities":
            {
              "entities": [
                {
                  "entityName": "Missed entity name",
                  "entityType": "Entity type",
                  "entityDescription": "Natural language description of the entity",
                  "entityInfo": ""
                }
              ]
            }
            
            Important Notes:
            1. Output complete JSON object only, no extra text or comments.
            2. If no entities are missed, output {"entities": []}.
            3. Choose entityType from [Person, Technology, Task, Organization, Location, Event, Time, Document, Concept].
            """;

    private static final String GenerationSingleHop_EN = """
            # Role: You are a high-quality QA dataset generation assistant for building test data for Retrieval-Augmented Generation (RAG) systems.
            # Task: Based on the given document name and content, generate 1-3 single-hop QA pairs and provide the corresponding evidence from the document.
            # Definition
            Single-hop questions are those that:
                - Can be answered using only one piece of information from the document
                - Do not require reasoning, computation, or combining multiple pieces of information
            # Requirements
                - Questions must be in natural language and reasonably detailed
                - Answers must be directly findable in the document
                - Do not fabricate information not present in the document
                - "according" should be the original text from the document that supports the answer
            
            # Input
            Document Name: %s
            Document Content: %s
            
            # Output Format (Strict JSON)
            [
                {
                    "question": "Your question",
                    "answer": "Answer",
                    "according": "Original text evidence"
                }
            ]
            """;

    private static final String GenerationSimpleDocMultiHop_EN = """
            # Role: You are a high-quality QA dataset generation assistant for building test data for Retrieval-Augmented Generation (RAG) systems.
            # Task: Based on the given document, generate 1-2 multi-hop QA pairs and provide the corresponding evidence from the document.
            # Definition
            Multi-hop questions are those that:
                - Require integrating **two or more independent pieces of information** from the document to answer
                - Require simple reasoning/association/combination; answers cannot be directly found from a single sentence
                - Pure single-hop or lookup questions are prohibited
            # Requirements
                - Questions must be in natural language (like real users would ask)
                - Answers must be derived from document content through reasoning; do not fabricate
                - Evidence must annotate all original text information points used
            # 
            Document Name: %s
            Document Content: %s
            
            # Output Format (Strict JSON)
            [
                {
                    "question": "Your question",
                    "answer": "Answer",
                    "according": "Original text evidence"
                }
            ]
            """;

    private static final String GenerationCrossDoc_EN = """
            # Role: You are a high-quality cross-document QA dataset generation assistant for building cross-document test data for Retrieval-Augmented Generation (RAG) systems.
            # Task: Based on **multiple related documents**, generate 3-4 **cross-document multi-hop QA pairs** and provide all supporting evidence.
            # Definition
            Cross-document multi-hop questions are those that:
                - Answers must **simultaneously depend on two or more independent documents** to be derived
                - Require integrating **multiple information points** from different documents for reasoning/association/computation/comparison
                - Complete answers cannot be directly found from any single document
                - Single-document questions and pure lookup questions are prohibited
            # Requirements
                - Questions must be in natural language (like real users would ask)
                - Answers must be strictly derived from all document content through reasoning; do not fabricate
                - Evidence must be **annotated per document** for all original text information used
            # Input
            Related Document Contents: %s
            
            # Output Format (Strict JSON, no extra text)
            [
                {
                    "question": "Cross-document question",
                    "answer": "Cross-document reasoning answer",
                    "according": "Document 1 evidence: xxx; Document 2 evidence: xxx"
                }
            ]
            """;

    private static final String GenerationSummaryLevel_EN = """
            Based on the following dataset description:
            %s
            Please identify 5 potential users who would use this dataset. For each user, list 3 tasks they would perform with this dataset. Then, for each (user, task) combination, generate 3 questions that require a high-level understanding of the entire dataset to answer.
            Please output the results in the following structure:
            - User 1: [User description]
                - Task 1: [Task description]
                    - Question 1:
                    - Question 2:
                    - Question 3:
                - Task 2: [Task description]
                ...
                - Task 3: [Task description]
            - User 2: [User description]
            ...
            - User 5: [User description]
            Strict Requirements:
                1. Questions must be self-contained. Do not use pronouns like "it", "this", "that". Specific objects must be explicitly named. For example: "How long is the probation period for this position?" should be written as "How long is the probation period for the Operations Engineer position at XX Company?"
                2. Answers and evidence must be generated based on actual dataset content. Do not fabricate.
                3. Do not ask specific questions whose answers can be found in a single document. For example: do not ask about specific times, numbers, amounts, etc.
            Output:
            {
                "users": [
                    {
                        "description": "User description",
                        "tasks": [
                            {
                                "fileId":"",
                                "description": "Task description",
                                "questions": [
                                    {
                                        "question": "Question content",
                                         "answer": "Reference answer",
                                        "according": "Supporting evidence"
                                    }
                                ]
                            }
                        ]
                    }
                ]
            }
            
            
            """;

    private static final String RetrievalPlanPrompt_EN = """
            # Role: You are a Question Analysis Agent.
            # Task: Analyze the user's question, determine the question type, extract keywords, and infer relevant topics.
            
            ## Detailed Tasks
            1. Determine the question type (queryType)
            2. Extract keywords (keywords)
            3. Extract relevant topics (topics) for topic-based retrieval
            
            ## I. Question Type Definitions (queryType)
            
            ### 1. MULTI_HOP
            Questions that require multi-step relational reasoning to answer, involving relational jumps across multiple entities.
            Characteristics:
            - Need to first find A, then use A to find B, then use B to find C
            - The question contains implicit intermediate reasoning steps, must span multiple entities to answer, cannot be answered through a single retrieval
            Examples:
            - What classified projects is John's department involved in?
            - What papers have Professor Chen's students at Peking University published?
            
            ### 2. GLOBAL_ABSTRACT
            Summary/synthesis questions: questions requiring high-level cognition such as macro-summarization, risk synthesis, or overall generalization.
            Examples:
                - What project risks existed in 2024?
                - What are the main issues in the current work?
            
            ### 3. SPECIFIC_DETAIL
            Questions asking for explicit facts, attributes, contact information, times, reasons, specific requirements, etc. — information that can be directly located.
            Characteristics:
            - Has a clear query target (a specific person, organization, time point, specific event)
            - Answers are typically specific descriptive content that can be found directly in documents
            - **Key Distinction**: Even if a question contains "why" or "what reason", as long as it asks about the cause/background/details of a specific event, it still falls under SPECIFIC_DETAIL and does not require multi-hop reasoning
            Examples:
            - What is John's phone number?
            - Who is the project lead for XX?
            - Where is the Computer Science department office?
            - What are the requirements regarding XX assessment?
            
            ## II. Keyword Extraction Rules (keywords)
             1. The keywords field should contain: person names, location names, organization names, project names, system names, domain-specific terminology, etc.
            
            ## III. Topic Extraction Rules (topics)
             1. Topics are high-level generalizations of the question domain, used for topic-based retrieval.
            Example:
            Question: "What are the project risks for 2024?"
            topics: ["project risk", "project management"]
            
            ### Strategy Adjustment by Diagnosis Type:
            
            #### WRONG_RELATION_PATH:
            - Keep queryType as MULTI_HOP unchanged
            - Adjust keywords and topics based on suggested relationships in suggestionDetail
            
            #### INSUFFICIENT_EXPANSION:
            - Keep queryType as MULTI_HOP unchanged
            - Supplement keywords and topics based on suggestionDetail
            
            #### WRONG_TOPICS:
            - Keep queryType as GLOBAL_ABSTRACT unchanged
            - Consider replacing topics with those suggested in suggestionDetail
            
            #### NEED_QUESTION_REWRITE:
            - Keep queryType unchanged
            - Re-extract keywords and topics based on the rewritten question in suggestionDetail
            
            #### WRONG_QUERY_TYPE:
            - Change queryType to the correct type suggested in suggestionDetail
            - Re-extract keywords and topics
            
            ## Reflection and Improvement Rules (Very Important)
                1. Which tools have already been called
                2. Which retrieval keywords have already been tried
                3. Which retrieval directions have already failed
                4. What issues were identified by the evaluation agent
            
            # Input
            User Question:
            %s
            Historical Execution Records and Diagnosis Results:
            %s
            
            # Output
            Must strictly follow this format (single-line JSON, no line breaks):
            {"queryType":"enum_value","keywords":["keyword1","keyword2"],"topics":["topic1","topic2"]}
            
            Output Rules:
            1. Must be a single-line valid JSON. No line breaks allowed.
            2. queryType must be one of: MULTI_HOP, GLOBAL_ABSTRACT, SPECIFIC_DETAIL
            3. Empty content arrays must be [], not null
            4. Do not add any explanations, notes, or extra text
            
            Example 1 (Multi-hop):
            {"queryType":"MULTI_HOP","keywords":["John","classified project"],"topics":["personnel-organization relationships","project management"]}
            
            Example 2 (Global Abstract):
            {"queryType":"GLOBAL_ABSTRACT","keywords":["project risk","2024"],"topics":["project risk management","risk assessment","project security"]}
            
            Example 3 (Specific Detail):
            {"queryType":"SPECIFIC_DETAIL","keywords":["John","phone"],"topics":["personnel contact information","staff directory"]}
            
            """;

    private static final String SystemCOT_EN = """
            You are a "Tool Calling Agent", **only responsible for selecting and calling retrieval tools**. Do NOT generate a final answer.
            
            Your sole responsibilities:
            1. Based on the structured question analysis results, determine which tools to call
            2. Call tools to acquire knowledge
            
            **Important: You only need to call tools. Once tool calls are complete, respond with plain text only (e.g. "retrieval complete"). Do NOT generate any answer text. The final answer will be generated separately in a later stage based on the retrieval results.
            You can ONLY use the tools provided to you. Do NOT invent or call any non-existent tool names (such as stop, finish, end, done, stop_tool_calling, etc.). If no suitable tool is available, simply output text to end this round.**
            
            [Tool Selection Rules by Question Type]
            
            1. SPECIFIC_DETAIL:
               Retrieval strategy:
               - Directly use searchFileByQuery(query=original question) to retrieve document content most semantically similar to the question
               - In addition,use searchEntityByName(names=entity-name list) to retrieve information of relevant entities
               - If document retrieval returns no results, try using topics for topic-based retrieval as a fallback
            
            2. MULTI_HOP:
               Available tool: getEntityTriples (returns entity attributes + up to 10 triples)
               Triple format: (Entity Name) -[Relation Type]-> (Neighbor Entity Name)
            
               MULTI_HOP reasoning steps:
            
               Step 1 (Locate the starting entity):
               - Use getEntityTriples(names=keywords) to obtain entity attributes and associated triples
               - Carefully read each returned triple, understanding what relationships the entity has
            
               Step 2 (Semantically match triples):
               - Based on the question semantics, find the most relevant triple from the list
               - Example: Question asks "What classified projects is John's department involved in?"
                 Returned triples: 1. (John) -[belongs_to]-> (Computer Science Dept.)  2. (John) -[advises]-> (Jane)
                 Select triple 1 ("belongs_to" indicates affiliation)
            
               Step 3 (Extract neighbor name from triple, continue expanding):
               - Extract the neighbor entity name (e.g., "Computer Science Dept.")
               - Use the neighbor name to call getEntityTriples(names=["Computer Science Dept."]) again
               - Continue matching question-relevant triples from the returned results (e.g., "participates_in" relationships)
               - Repeat this step until sufficient information is obtained
            
               Auxiliary tools:
               - searchFileByQuery: When graph information is insufficient, use the user's original question for full-text document retrieval as a supplement
            
            3. GLOBAL_ABSTRACT:
               Retrieval strategy:
               - Prioritize searchFileByTopic with topics + query (original question)
               - Can use searchFileByQuery as supplementary full-text semantic retrieval
            
            [Tool Calling Constraints]
            - Use at least one retrieval tool per round
            - When calling tools, prioritize using content from the "Question Analysis Agent" results as parameter sources
            - Do not call the same tool with semantically identical parameters repeatedly
            - If a tool has already returned results for a given semantic query, you must either:
              1. Switch retrieval dimensions (entity → topic → query)
              2. Or directly stop calling that tool
            - Do not repeat queries by merely rewording or adding/removing meaningless words
            - Each tool call must introduce new informational value
            - When keyword retrieval yields no results, try topic-based retrieval using topics
            - Multi_hop questions: Use getEntityTriples to obtain triples, extract neighbor entity names from triples, and call getEntityTriples again with neighbor names to continue expansion
            
            [Tool Call Limit] (strictly follow to prevent context overflow)
            - Maximum 4 tool calls in total. Once the limit is reached, respond with plain text ONLY — do NOT call any more tools.
            - MULTI_HOP questions: getEntityTriples max 3 calls, searchFileByQuery max 1 call as supplement.
            - SPECIFIC_DETAIL questions: searchFileByQuery/searchFileByTopic/searchEntityByName combined max 2 calls.
            - After the 4th call, output plain text regardless of results — do NOT call any tools.
            
            You must prioritize using tools. When all tool calls are complete, output plain text only (e.g. "retrieval complete"). Do NOT output any answer content. Do NOT invent any tool names.
            """;

    private static final String RetrievalPrompt_EN = """
            # Role: You are an "Retrieval Decision and Tool Calling Agent".
            
            # Task:
                1. Based on the "Question Analysis Agent" results, select retrieval tools and use the analysis results as tool parameters.
                2. Autonomously determine which retrieval tools to call.
                3. Consider which tool is more likely to obtain answers to the question/sub-questions.
                4. **Your only task is to call tools for retrieval. Do NOT generate a final answer.** The final answer will be handled separately in a later stage.
            
            # Question Analysis Agent Results
            User Question: %s
            Question Type: %s
            Keywords: %s
            Related Topics: %s
            
            Based on the above analysis results, autonomously select retrieval tools. Only call tools — do not output any answer text.
            """;

    private static final String AutonomousRetrievalPrompt_EN = """
            # Role: You are an "Retrieval Decision and Tool Calling Agent".

            # Task:
                1. Based on the user's question, autonomously select and call appropriate retrieval tools.
                2. Consider which tool is more likely to obtain the answer to the question.
                3. **Your only task is to call tools for retrieval. Do NOT generate a final answer.** The final answer will be handled separately in a later stage.

            # User Question
            %s

            Based on the user's question, autonomously select retrieval tools. Only call tools — do not output any answer text.
            """;

    private static final String AgentEvaluation_EN = """
            # Role: Answer Evaluation Expert. Determine whether the initial answer can address the user's question, and provide structured diagnosis upon failure.
            
            ## Core Tasks:
            1. Determine whether the answer can address the user's question (PASS/FAIL)
            2. On FAIL, must provide diagnosis information to guide the next round's strategy adjustment
            
            ## Diagnosis Rules:
            
            ### 1. Multi-hop Questions (using getEntityTriples tool)
            - Relation selection does not match question semantics → WRONG_RELATION_PATH
            - Insufficient expansion hops → INSUFFICIENT_EXPANSION
            
            ### 2. GLOBAL_ABSTRACT Questions (using searchFileByTopic tool)
            - Inappropriate topics → WRONG_TOPICS
            - Need more precise expression → NEED_QUESTION_REWRITE
            
            ### 3. Wrong Question Type
            - Question type defined by the Question Analysis Agent does not match actual needs → WRONG_QUERY_TYPE
            
            Fill suggestionDetail with content corresponding to the problemType (e.g., relation semantic description, suggested topics, rewritten question, correct type, etc.).
            nextRoundStrategy must provide a concrete executable strategy for the next round.
            
            # Input:
            User Question: %s
            Current Round Question Analysis Results: %s
            Initial Answer: %s
            Answer Evidence (Tool Retrieval Results):
            %s
            
            # Output JSON (single line, no extra text):
            {"success":"PASS or FAIL","reason":"reason","diagnosis":{"problemType":"WRONG_RELATION_PATH|INSUFFICIENT_EXPANSION|WRONG_TOPICS|NEED_QUESTION_REWRITE|WRONG_QUERY_TYPE","detail":"diagnosis explanation","suggestionDetail":"modification suggestion","nextRoundStrategy":"strategy suggestion"}}
            
            Examples:
            PASS: {"success":"PASS","reason":"The answer accurately and completely addresses the question","diagnosis":null}
            
            WRONG_RELATION_PATH: {"success":"FAIL","reason":"The question asks about the department's classified projects, but the 'advises student' relation was selected, which is irrelevant to the question","diagnosis":{"problemType":"WRONG_RELATION_PATH","detail":"Selected 'advises student', but the core is department → classified projects. Should semantically match the affiliation relation from actual relations","suggestionDetail":"Suggested relations: relations semantically indicating affiliation/belonging, relations semantically indicating project participation","nextRoundStrategy":"Change semantic direction: first obtain the department, then match project participation relations from the department's relations"}}
            
            WRONG_TOPICS: {"success":"FAIL","reason":"Topic 'project risk management' returned no results, too narrow","diagnosis":{"problemType":"WRONG_TOPICS","detail":"Current topic has no matches","suggestionDetail":"Suggested topics: project security, annual report, risk audit","nextRoundStrategy":"Switch topics: search with ['project security','annual report'], supplemented by searchFileByQuery"}}
            
            WRONG_QUERY_TYPE: {"success":"FAIL","reason":"Question Analysis Agent defined as MULTI_HOP, but asking for John's phone number is a specific detail question. The type definition is wrong.","diagnosis":{"problemType":"WRONG_QUERY_TYPE","detail":"Specific fact queries do not require multi-hop reasoning. The Question Analysis Agent's queryType definition is incorrect.","suggestionDetail":"Correct type: SPECIFIC_DETAIL","nextRoundStrategy":"Reclassify as SPECIFIC_DETAIL, search using searchFileByQuery"}}
            
            INSUFFICIENT_EXPANSION: {"success":"FAIL","reason":"Only expanded to the student level, did not retrieve paper information","diagnosis":{"problemType":"INSUFFICIENT_EXPANSION","detail":"Completed teacher → student hop, but still need student → paper as the second hop","suggestionDetail":"Suggested relations: relations semantically indicating publication/authorship","nextRoundStrategy":"Continue expansion: for each student node, match relations indicating publication from actual relations"}}
            """;

    private static final String BaselinePrompt_EN = """
            
            # Task: Answer the user's question based on the retrieved document content.
            
            # Requirements
            1. Answers must be **entirely based on the retrieved document content**. Do not fabricate, extrapolate, or use external knowledge.
            2. If the documents do not contain relevant information, respond directly: "Based on the provided documents, no relevant information was found."
            
            # Input:
            User Question: %s
            Relevant Documents: %s
            """;

    private static final String IntentAnswerPrompt_EN = """
            # Role: You are an intelligent retrieval-based QA assistant, skilled at synthesizing multi-source retrieval results to provide accurate, comprehensive, and well-grounded answers to user questions.
            
            ## Core Capabilities
            1. **Information Integration**: You can comprehensively analyze results from different retrieval tools, such as semantic search, topic retrieval, and knowledge graph queries, and extract both shared and complementary information.
            2. **Truth Filtering**: You can identify redundant or contradictory information in the retrieval results, and prioritize the content that is most relevant to the question and most reliable based on the provided evidence.
            3. **Structured Expression**: You can choose an appropriate answer format according to the question type, such as lists, bullet points, or comparison tables.
            
            ## Answering Principles
            1. **Clear and Logical**: The answer should be logically structured. First provide the core conclusion, then expand with detailed supporting evidence.
            2. **Information Summarization**: Merge and summarize repeated information; supplement and integrate complementary information.
            3. **Complex Question Handling**: For complex or multi-hop questions, first identify the multiple sub-questions involved, then integrate the relevant evidence for each sub-question, and finally provide a comprehensive conclusion.
            ## Input
            User Question: %s
            
            Retrieval Results:
            %s
            
            """;

    private static final String AssessUserPrompt_EN = """
            # Task:
            Your task is to evaluate the following answer based on four criteria. Score each criterion on a scale of 1 to 10, strictly following the detailed scoring guidelines.
            # Note:
            Scores must be supported by direct quotes from the answer text. Avoid vague or generic descriptions. All explanations must reference actual text.
            # Criteria Description
            - Content Comprehensiveness (contentComprehensiveness)
            Does the answer provide details that completely cover all aspects and sub-points of the question?
            - Empowerment (empowerment)
            Does the answer effectively help readers understand the relevant topic and support them in making objective, well-reasoned judgments?
            - Diversity (diversity)
            Does the answer present information from diverse perspectives, covering multiple relevant angles?
            - Overall Quality (overall)
            Comprehensive evaluation combining the above three criteria, balancing content quality with the practical value of the answer for the original question.
            
            ## Scoring Scale
            1-2: Very poor — significant deficiencies in this criterion, serious content issues;
            3-4: Weak — multiple key content gaps, obvious problems;
            5-6: Average — barely meets basic answering requirements, minor flaws present;
            7-8: Good — overall strong performance, only minor shortcomings;
            9-10: Excellent — outstanding performance in this dimension, no apparent deficiencies.
            
            
            # Input
            Question to Evaluate: %s
            Answer to Evaluate: %s
            
            Evaluate the answer using the  criteria listed above and provide detailed explanations for each criterion with reference to the text.\s
            Output your evaluation in the following JSON format:
            {{
                 "Comprehensiveness": {{
                       "score": "[1-10]",
                       "Explanation": "[Provide explanation here]"         
                 }},
                 "Empowerment": {{
                       "score": "[1-10]",
                       "Explanation": "[Provide explanation here]"
                 }},
                 "Diversity": {{
                      "score": "[1-10]",
                      "Explanation": "[Provide explanation here]"
                 }},
                "Overall Quality": {{
                       "score": "[1-10]",
                       "Explanation": "[Summarize why this answer is the overall winner based on the three criteria]"
                 }}
            }}       
                            
            """;
}
