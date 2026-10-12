import { inject, onUnmounted, provide, ref, watch, type InjectionKey, type Ref } from 'vue';
import { request } from './api';

type PublicEvent = {projectId:string;eventSeq:string;eventId:string;conversationId?:string;taskId?:string;attemptId?:string;payload:Record<string,unknown>};
type Listener = (event:PublicEvent|null)=>Promise<void>;
const key:InjectionKey<{listen:(listener:Listener)=>()=>void}>=Symbol('project-events');
const types=['turn.accepted','run.started','activity.updated','assistant.delta','assistant.completed','run.finished','run.failed','run.cancelled','asset.updated','analysis.stage.updated','plan_revision.created','artifact.available','artifact.removed','question.created','question.closed'];

/** The workspace owns one connection, with successful object dispatch before cursor advance. */
export function provideProjectEvents(project:Ref<string|undefined>){
  const listeners=new Set<Listener>();
  provide(key,{listen(listener){listeners.add(listener);return()=>listeners.delete(listener);}});
  const status=ref('');
  let source:EventSource|undefined, generation=0, streamGeneration=0, retry:ReturnType<typeof setTimeout>|undefined;
  function close(){streamGeneration++;source?.close();source=undefined;clearTimeout(retry);}
  async function dispatch(event:PublicEvent|null){await Promise.all([...listeners].map(listener=>listener(event)));}
  watch(project,async id=>{
    const current=++generation;close();status.value='';if(!id)return;
    let cursor='',sequence=0n,queue=Promise.resolve();
    async function restore(){
      close();if(current!==generation)return;
      try{
        const snapshot=await request<{cursor:string}>(`/projects/${id}/snapshot`);
        if(current!==generation)return;
        cursor=snapshot.cursor;sequence=BigInt(cursor.split(':')[1]!);
        await dispatch(null);
        if(current!==generation)return;
        connect();
      }catch(failure){
        if(current!==generation)return;
        status.value=failure instanceof Error?failure.message:'连接暂时中断，正在恢复';
        retry=setTimeout(()=>void restore(),2000);
      }
    }
    function connect(){
      if(current!==generation)return;
      const connection=streamGeneration;
      source=new EventSource(`/api/v1/projects/${id}/events?after=${encodeURIComponent(cursor)}`);
      source.onopen=()=>{if(current===generation)status.value='';};
      for(const type of types)source.addEventListener(type,raw=>{
        const message=raw as MessageEvent<string>;
        queue=queue.then(async()=>{
          if(current!==generation || connection!==streamGeneration)return;
          const event=JSON.parse(message.data) as PublicEvent;
          if(event.projectId!==id || !/^\d+$/.test(event.eventSeq) || message.lastEventId!==`${id}:${event.eventSeq}`)throw new Error('事件恢复校验失败');
          const next=BigInt(event.eventSeq);if(next<=sequence)return;
          await dispatch(event);
          if(current!==generation || connection!==streamGeneration)return;
          sequence=next;cursor=message.lastEventId;
        }).catch(()=>{if(current===generation && connection===streamGeneration)void restore();});
      });
      source.addEventListener('stream.reset',()=>{if(current===generation)void restore();});
      source.addEventListener('stream.auth-required',()=>{
        if(current!==generation)return;close();window.dispatchEvent(new Event('auth-required'));
      });
      source.onerror=()=>{
        if(current!==generation)return;
        close();status.value='连接暂时中断，正在恢复';
        // Native EventSource hides HTTP 410. Restore authoritative state and its
        // cursor before reconnecting, including when retention ran while offline.
        retry=setTimeout(()=>void restore(),1000);
      };
    }
    await restore();
  },{immediate:true});
  onUnmounted(()=>{generation++;close();listeners.clear();});
  return status;
}

export function useProjectEvents(listener:Listener){
  const bus=inject(key);const stop=bus?.listen(listener);onUnmounted(()=>stop?.());
}
