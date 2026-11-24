import { expect, test } from 'bun:test';
import { phoneProjection } from '../src/phone-projection.ts';
const system = { role: 'system', timestamp: 123, sections: ['p'.repeat(43000)], toolsAdded: ['t'.repeat(55000)] };
const entry = { type: 'message', id: 'system-id', parentId: 'prior', message: system };
const assistant = { role: 'assistant', content: [{ type: 'toolCall', name: 'read', arguments: { role: 'system', sections: 'user data' } }] };
const compact = { role: 'system', content: [], timestamp: 123 };
test('cold-start system catalog is smaller than one fragment in every live representation', () => {
 for (const type of ['message_start','message_end','turn_end']) expect(phoneProjection('event',{type,message:system})).toEqual({type,message:compact});
 const projected = phoneProjection('event',{type:'entry_appended',entry});
 expect(projected).toEqual({type:'entry_appended',entry:{...entry,message:compact}});
 expect(JSON.stringify(projected).length).toBeLessThan(256);
 expect(phoneProjection('event',{type:'agent_end',messages:[system,assistant]})).toEqual({type:'agent_end',messages:[compact,assistant]});
 expect(system.sections[0].length).toBe(43000);
});
test('history and recovery keep branch identity, assistant calls, and request payloads intact', () => {
 const a = {...entry,id:'call',parentId:entry.id,message:assistant};
 for (const kind of ['snapshot','entries','settled']) expect(phoneProjection(kind,{entries:[entry,a],leafId:'call'})).toEqual({entries:[{...entry,message:compact},a],leafId:'call'});
 const event = {type:'message_update',assistantMessageEvent:{type:'toolcall_start', toolName:'read'}};
 expect(phoneProjection('event',event)).toBe(event);
});
