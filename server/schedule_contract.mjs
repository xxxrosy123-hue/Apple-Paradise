/** Phase 5 transport contract, shared by Node MCP and Cloudflare MCP.
 * Android owns facts. This module validates explicit arguments, never judges user activity.
 */
export const SCHEDULE_SOURCE_OF_TRUTH = "android_local:schedule_state_v1";
export const SCHEDULE_QUERY_DESCRIPTION = "读取苹果乐园独立 Schedule 时间轴，按手机本地日期或绝对范围查询 plan/actual。计划不是实际，Todo deadline 不是计划时间。返回 Android 正式事实及可靠 Focus completed-session 投影；不把推测写成 actual，不修改 Todo 完成状态。历史使用分页查询，Server 只传输命令和缓存摘要。";
export const SCHEDULE_ACTION_DESCRIPTION = "创建、修改、删除计划或用户确认的实际时间块。plan 表示安排，不代表已经发生；deadline 与 Schedule 不同。actual 只能来自用户明确记录、可靠实际来源或用户确认后的 AI 补录，禁止把聊天、前台 App、UsageStats 推测直接写成正式 actual。Focus 由 Android 自动投影，不可伪造 focus_session 来源。用户修改的 actual 优先，普通同步不得覆盖；同一来源幂等，删除来源投影会保留排除记录。MCP create 必须提供可复用 idempotency_key，或提供稳定的 schedule_ 领域 id；重试必须复用同一值。transport command id、block id 与 idempotency key 含义不同。restore 仅适用于仍有来源的 Focus 投影或单次重复 occurrence，不承诺恢复普通手工块或已删除 series。Schedule 不自动 complete/reopen Todo，Server 不替 AI 做主观判断。重复计划只支持 daily/weekly/指定星期；修改单次用 occurrence ID+scope=this，修改整个规则用 series ID+scope=all；future 未实现。";
export const SCHEDULE_QUERY_FIELDS = ["id","date","view","timezone","from_ms","to_ms","kind","todo_id","offset","limit"];
export const SCHEDULE_ACTION_FIELDS = ["operation","id","idempotency_key","kind","title","note","category","color","start_at_ms","end_at_ms","todo_id","reminder_minutes_before","repeat","scope","confirmed_by_user","user_confirmed"];
const str = (description, options = {}) => ({ type: "string", description, ...options });
const integer = (description, options = {}) => ({ type: "integer", description, ...options });
const boolean = (description) => ({ type: "boolean", description });
export const SCHEDULE_REPEAT_SCHEMA = {
  type: "object", additionalProperties: false,
  properties: {
    frequency: str("none/daily/weekly", {enum:["none","daily","weekly"]}),
    timezone: str("IANA 时区，例如 Asia/Tokyo；固定本地时刻按该时区日历展开"),
    anchor_date: str("yyyy-MM-dd，重复开始的本地日期"),
    start_time: str("HH:mm，本地墙上时刻"), end_time: str("HH:mm，本地墙上时刻"),
    end_day_offset: integer("结束相对开始日期的天数，跨午夜睡眠通常为 1",{minimum:0,maximum:366}),
    weekdays: {type:"array",items:{type:"integer",minimum:1,maximum:7},description:"ISO 星期 1=周一…7=周日"},
    until_date: str("可选包含最后发生日期 yyyy-MM-dd；空表示无限规则，查询按需展开")
  }, required:["frequency"]
};
export const SCHEDULE_QUERY_SCHEMA = {
  type:"object",additionalProperties:false,properties:{
    id:str("可选稳定时间块/occurrence/series ID；提供时返回该正式块，找不到则 block=null"),
    date:str("手机本地日期 yyyy-MM-dd，默认今天"),view:str("day/week",{enum:["day","week"],default:"day"}),timezone:str("查询日期所用 IANA 时区，默认手机本地时区"),
    from_ms:integer("绝对范围开始，epoch 毫秒；须与 to_ms 一起传"),to_ms:integer("绝对范围结束，半开区间"),
    kind:str("plan/actual/all",{enum:["plan","actual","all"],default:"all"}),todo_id:str("可选正式 Todo ID"),
    offset:integer("分页偏移",{minimum:0,default:0}),limit:integer("单页上限500，不会截断本地历史",{minimum:1,maximum:500,default:100}),
    device_id:str("设备ID",{default:"android-phone"}),wait_seconds:integer("等待手机命令返回的秒数",{minimum:3,maximum:20,default:8})
  }
};
export const SCHEDULE_ACTION_SCHEMA = {
  type:"object",additionalProperties:false,properties:{
    operation:str("操作；restore 仅支持仍有来源的 Focus 投影或单次重复 occurrence",{enum:["create","update","delete","restore"]}),
    id:str("稳定 block/occurrence/series ID；create 可用 schedule_ ID 作为幂等身份"),
    idempotency_key:str("MCP create 的稳定幂等键，8～128 位字母、数字、点、下划线、冒号或短横；超时重试必须复用",{minLength:8,maxLength:128,pattern:"^[A-Za-z0-9._:-]+$"}),
    kind:str("create 必填，plan 或 actual；更新不能改变 kind",{enum:["plan","actual"]}),
    title:str("时间块标题"),note:str("可选备注"),category:str("自由分类，不绑定硬编码应用类别"),color:str("可选 #RRGGBB"),
    start_at_ms:integer("绝对开始时间 epoch 毫秒"),end_at_ms:integer("绝对结束时间 epoch 毫秒，必须大于开始"),
    todo_id:str("可选正式 Todo ID；手机本地验证，不按标题猜，不改变 deadline/completion"),
    reminder_minutes_before:integer("计划提前提醒分钟，-1关闭，0开始时；本地非精确提醒",{minimum:-1,maximum:43200}),
    repeat:SCHEDULE_REPEAT_SCHEMA,scope:str("重复规则修改/删除范围：this 或 all；future 未实现",{enum:["this","all"]}),
    confirmed_by_user:boolean("创建 actual 必须明确用户已确认该实际事实；不能以 AI 推测代替"),
    user_confirmed:boolean("编辑 actual 必须明确用户已确认修正；普通同步不能覆盖"),
    device_id:str("设备ID",{default:"android-phone"}),wait_seconds:integer("等待手机命令返回秒数",{minimum:3,maximum:20,default:8})
  },required:["operation"]
};
function fail(message){throw new Error(message);}
function integerValue(value,key){if(!Number.isSafeInteger(value))fail("schedule_invalid_"+key);return value;}
function select(args,fields){const out={};for(const key of fields)if(Object.prototype.hasOwnProperty.call(args,key)&&args[key]!==undefined)out[key]=args[key];return out;}
export function normalizeScheduleQuery(args={}) {
  const out=select(args,SCHEDULE_QUERY_FIELDS);
  if(out.from_ms!==undefined||out.to_ms!==undefined){integerValue(out.from_ms,"from_ms");integerValue(out.to_ms,"to_ms");if(out.from_ms<=0||out.to_ms<=out.from_ms)fail("schedule_query_range_invalid");}
  if(out.date!==undefined&&!/^\d{4}-\d{2}-\d{2}$/.test(out.date))fail("schedule_date_invalid");
  if(out.view!==undefined&&!(["day","week"].includes(out.view)))fail("schedule_view_invalid");
  if(out.kind!==undefined&&!(["all","plan","actual"].includes(out.kind)))fail("schedule_kind_invalid");
  if(out.offset!==undefined&&(integerValue(out.offset,"offset")<0))fail("schedule_offset_invalid");
  if(out.limit!==undefined&&(integerValue(out.limit,"limit")<1||out.limit>500))fail("schedule_limit_invalid");
  return out;
}
export function normalizeScheduleAction(args={}) {
  const out=select(args,SCHEDULE_ACTION_FIELDS);
  const op=out.operation;
  if(!["create","update","delete","restore"].includes(op))fail("schedule_operation_invalid");
  if(op!=="create"&&!String(out.id||"").trim())fail("schedule_id_required");
  if(op!=="create"&&out.idempotency_key!==undefined)fail("schedule_idempotency_create_only");
  if(op==="create"){
    if(!["plan","actual"].includes(out.kind))fail("schedule_kind_required");
    if(!String(out.title||"").trim())fail("schedule_title_required");
    const requestedId=String(out.id||"").trim();
    if(requestedId&&!requestedId.startsWith("schedule_"))fail("schedule_id_invalid");
    let key=String(out.idempotency_key||"").trim();
    if(!key&&requestedId)key="domain:"+requestedId;
    if(!key)fail("schedule_idempotency_required");
    if(key.length<8||key.length>128||!/^[A-Za-z0-9._:-]+$/.test(key))fail("schedule_idempotency_key_invalid");
    out.idempotency_key=key;
    integerValue(out.start_at_ms,"start_at_ms");integerValue(out.end_at_ms,"end_at_ms");
    if(out.start_at_ms<=0||out.end_at_ms<=out.start_at_ms)fail("schedule_invalid_time_range");
  }
  if(out.kind==="actual"&&op==="create"&&!out.confirmed_by_user)fail("schedule_actual_requires_explicit_confirmation");
  if(op==="update"&&out.kind!==undefined)fail("schedule_immutable_field:kind");
  if(["update","delete"].includes(op)&&out.scope!==undefined&&!(["this","all"].includes(out.scope)))fail("schedule_scope_invalid");
  if(out.start_at_ms!==undefined)integerValue(out.start_at_ms,"start_at_ms");
  if(out.end_at_ms!==undefined)integerValue(out.end_at_ms,"end_at_ms");
  if(out.reminder_minutes_before!==undefined&&(integerValue(out.reminder_minutes_before,"reminder_minutes_before")< -1||out.reminder_minutes_before>43200))fail("schedule_reminder_invalid");
  if(out.repeat!==undefined){if(op==="create"&&out.kind!=="plan")fail("schedule_repeat_plan_only");if(out.repeat===null||typeof out.repeat!=="object"||Array.isArray(out.repeat))fail("schedule_repeat_invalid");}
  // The tool is an AI transport. No caller may claim to be the local user or a Focus source.
  out.actor="ai";
  if(op==="create"){
    out.source=out.kind==="actual"?"ai_confirmed":"ai";
    if(out.kind==="actual")out.user_confirmed=true;
  }
  return out;
}
export function scheduleCommandEnvelope(action,args={},device_id="android-phone") {
  const payload={...args};
  delete payload.device_id;delete payload.wait_seconds;
  if(Object.prototype.hasOwnProperty.call(payload,"id")) {
    payload.block_id=payload.id;delete payload.id;
  }
  // Domain block_id must never overwrite the transport command UUID.
  return {action,device_id,...payload,payload:{...payload}};
}
export function parseSchedulePhoneResult(command) {
  if(!command||!command.result)return null;
  if(typeof command.result==="object")return command.result;
  try{return JSON.parse(command.result);}catch{return {ok:false,result:"schedule_phone_result_invalid"};}
}
export function scheduleTransportResult(queued,observed) {
  const command=observed?.command||queued?.command||null;
  const phone_result=parseSchedulePhoneResult(command);
  const completed=command?.status==="completed";
  const ok=completed&&phone_result?.ok===true;
  return {ok,queued,observed_status:command,phone_result,source_of_truth:SCHEDULE_SOURCE_OF_TRUTH,
    note:"Android 本地为正式事实源；Server 仅传输命令和缓存有新鲜度的摘要。未完成的队列命令不是已成功写入。"};
}
