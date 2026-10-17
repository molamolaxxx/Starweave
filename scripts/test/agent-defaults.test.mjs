import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import vm from 'node:vm';
import test from 'node:test';

const source=readFileSync(new URL('../../cmd-proxy-app/src/main/resources/configui/assets/js/agents.js',import.meta.url),'utf8');
function harness(){
  const elements={};
  const context=vm.createContext({console,config:{robots:[]},document:{
    addEventListener(){},querySelectorAll(){return []},
    getElementById(id){if(id.endsWith('Combobox')||id.endsWith('Menu'))return null;return elements[id]??={value:'',innerHTML:'',style:{},classList:{remove(){}}}}
  },esc:String,isPiProvider:()=>false,isNpmProvider:()=>false,providerVersionOptions:()=>'',dshPresetOptions:()=>'',dshBundleView:()=>'',showDialog(){}});
  vm.runInContext(source,context);
  // Rendering unrelated lists and tabs is outside this form/defaults harness.
  for(const name of ['renderTeamSharedOwners','renderSubAgents','renderContacts','clearRobotValidation','switchRobotTab','showDialog'])context[name]=()=>{};
  context.robotModelRequest=()=>({});
  return {context,elements};
}
const live=id=>({id,source:'api'});
const reply=models=>({ok:true,json:async()=>({models})});

test('new form enables schedule, observations, sleep and memory',()=>{
  const {context,elements}=harness();context.loadRobotModels=()=>{};
  context.openRobotDialog(-1);
  for(const id of ['dSchedule','dObservation','dAutoSleep','dMemRead','dMemWrite']){
    assert.match(elements.dlgBody.innerHTML,new RegExp('id="'+id+'" checked'));
  }
  assert.equal(context.robotMemoryModelAutoSelect,true);
});

test('edit and copy retain disabled features and do not auto select memory model',()=>{
  const {context,elements}=harness();context.loadRobotModels=()=>{};
  context.nextRobotCopyName=name=>name+' copy';
  context.config.robots=[{name:'existing',workDir:'/tmp',agentProvider:'JAVA_PI_ACP',scheduleEnabled:false,observationEnabled:false,autoSleep:{enabled:false}}];
  for(const mode of ['edit','copy']){
    context.openRobotDialog(0,mode);
    for(const id of ['dSchedule','dObservation','dAutoSleep'])assert.doesNotMatch(elements.dlgBody.innerHTML,new RegExp('id="'+id+'" checked'));
    context.applyDefaultMemoryModel([live('chat-mini')]);
    assert.equal(elements.dMemModel.value,'');
    assert.equal(context.robotMemoryModelAutoSelect,false);
  }
});

test('only explicit small text models from discovery qualify',()=>{
  const {context}=harness();
  for(const id of ['vendor/chat-mini','chat-nano','claude-haiku-4','gemini-flash','chat-lite','chat-small','gpt-6-luna','vendor/GPT-6-LUNA'])assert.equal(context.selectSmallMemoryModel([live(id)]),id);
  for(const model of [live('chat-large'),live('minimax-m2'),live('embedding-mini'),live('image-flash'),live('audio-mini'),{id:'chat-mini',source:'history'},{id:'chat-mini',source:'configured'}])assert.equal(context.selectSmallMemoryModel([model]),'');
  assert.equal(context.selectSmallMemoryModel([]),'');
});

test('catalog load selects a small model, provider changes clear previous auto choice',async()=>{
  const {context,elements}=harness();context.robotMemoryModelAutoSelect=true;
  context.api=async()=>reply([live('chat-large'),live('chat-mini')]);
  await context.loadRobotModels(false);assert.equal(elements.dMemModel.value,'chat-mini');
  context.api=async()=>reply([live('other-large')]);
  await context.loadRobotModels(false);assert.equal(elements.dMemModel.value,'');
});

test('manual input and deliberate clearing survive catalog refresh',async()=>{
  const {context,elements}=harness();context.robotMemoryModelAutoSelect=true;
  context.api=async()=>reply([live('chat-mini')]);await context.loadRobotModels(false);
  for(const value of ['custom-model','']){
    elements.dMemModel.value=value;context.filterRobotModelMenu('dMemModel');
    await context.loadRobotModels(true);assert.equal(elements.dMemModel.value,value);
  }
});

test('choosing a dropdown option disables automatic selection',async()=>{
  const {context,elements}=harness();context.robotMemoryModelAutoSelect=true;
  context.api=async()=>reply([live('chat-large'),live('chat-mini')]);await context.loadRobotModels(false);
  elements.dMemModel.focus=()=>{};
  context.selectRobotModelOption('dMemModel',{getAttribute:()=> '0'});
  await context.loadRobotModels(true);assert.equal(elements.dMemModel.value,'chat-large');
});

test('discovery failure leaves automatic field empty',async()=>{
  const {context,elements}=harness();context.robotMemoryModelAutoSelect=true;
  context.api=async()=>reply([live('chat-mini')]);await context.loadRobotModels(false);
  context.api=async()=>{throw new Error('offline')};
  await context.loadRobotModels(true);assert.equal(elements.dMemModel.value,'');
  context.api=async()=>({ok:true,json:async()=>({models:[live('cached-mini')],warning:'discovery failed'})});
  await context.loadRobotModels(true);assert.equal(elements.dMemModel.value,'');
});

test('late responses do not overwrite newer catalog or manual input',async()=>{
  const {context,elements}=harness();context.robotMemoryModelAutoSelect=true;
  let resolveOld;context.api=()=>new Promise(resolve=>{resolveOld=resolve});
  const pending=context.loadRobotModels(false);
  context.api=async()=>reply([live('new-mini')]);await context.loadRobotModels(false);
  resolveOld(reply([live('old-mini')]));await pending;
  assert.equal(elements.dMemModel.value,'new-mini');
  let resolveNext;context.api=()=>new Promise(resolve=>{resolveNext=resolve});
  const next=context.loadRobotModels(false);
  elements.dMemModel.value='';context.filterRobotModelMenu('dMemModel');
  resolveNext(reply([live('late-mini')]));await next;
  assert.equal(elements.dMemModel.value,'');
});
