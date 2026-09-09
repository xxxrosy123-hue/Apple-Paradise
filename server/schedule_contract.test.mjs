import assert from "node:assert/strict";
import {
  SCHEDULE_ACTION_DESCRIPTION,
  SCHEDULE_ACTION_SCHEMA,
  normalizeScheduleAction,
  scheduleCommandEnvelope,
  scheduleTransportResult
} from "./schedule_contract.mjs";

assert.throws(() => normalizeScheduleAction({
  operation:"create",kind:"plan",title:"学习",start_at_ms:1000,end_at_ms:2000
}), /schedule_idempotency_required/);

const normalized=normalizeScheduleAction({
  operation:"create",kind:"plan",title:"学习",start_at_ms:1000,end_at_ms:2000,
  idempotency_key:"schedule-create-001"
});
assert.equal(normalized.actor,"ai");
assert.equal(normalized.source,"ai");
assert.equal(normalized.idempotency_key,"schedule-create-001");

const stableId=normalizeScheduleAction({
  operation:"create",id:"schedule_client_stable",kind:"plan",title:"工作",start_at_ms:3000,end_at_ms:4000
});
assert.equal(stableId.idempotency_key,"domain:schedule_client_stable");
assert.throws(() => normalizeScheduleAction({operation:"update",id:"schedule_x",idempotency_key:"update-key-001"}),/schedule_idempotency_create_only/);

const envelope=scheduleCommandEnvelope("schedule_action",normalized,"android-phone");
assert.equal(envelope.action,"schedule_action");
assert.equal(envelope.idempotency_key,"schedule-create-001");
assert.equal(envelope.payload.idempotency_key,"schedule-create-001");
assert.equal("block_id" in envelope,false);

const withBlockId=scheduleCommandEnvelope("schedule_action",{...normalized,id:"schedule_client_stable"},"android-phone");
assert.equal(withBlockId.block_id,"schedule_client_stable");
assert.equal(withBlockId.payload.block_id,"schedule_client_stable");
assert.notEqual(withBlockId.block_id,withBlockId.idempotency_key);

const transport=scheduleTransportResult(
  {command:{id:"transport-command-1"}},
  {command:{id:"transport-command-2",status:"completed",result:JSON.stringify({ok:true,block:{id:"schedule_client_stable"},idempotency_replayed:true})}}
);
assert.equal(transport.ok,true);
assert.equal(transport.phone_result.block.id,"schedule_client_stable");
assert.equal(transport.observed_status.id,"transport-command-2");
assert.match(SCHEDULE_ACTION_DESCRIPTION,/restore 仅适用于仍有来源/);
assert.ok(SCHEDULE_ACTION_SCHEMA.properties.idempotency_key);

console.log("ScheduleContractBehaviorTest: PASS");
