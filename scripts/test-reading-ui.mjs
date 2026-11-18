import {readFileSync, writeFileSync, mkdirSync} from 'node:fs';
import {execFileSync} from 'node:child_process';
import {resolve} from 'node:path';
import {pathToFileURL} from 'node:url';

const root = resolve(import.meta.dirname, '..');
const output = resolve(root, '.qa-reading');
mkdirSync(output, {recursive: true});
const browser = process.env.CHROME_PATH || 'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe';
const prelude = `<script>
window.__calls=[];window.__question=1;window.SOTV_MATH=function(){};
window.SOTVNative={deviceInfo:function(){return '{"platform":"android-tv"}'},readInLanguage:function(text){window.__calls.push(text)},stopReading:function(){}};
window.XMLHttpRequest=function(){};
XMLHttpRequest.prototype.open=function(method,path){this.path=path};
XMLHttpRequest.prototype.setRequestHeader=function(){};
XMLHttpRequest.prototype.send=function(){var self=this;setTimeout(function(){var data={};
if(self.path==='/api/state')data={config:{supervisionEnabled:true,playMode:'practice',fontScale:100,roundsPerDay:3},learning:{reason:'timeup',canExit:false},speech:{autoRead:false}};
if(self.path==='/api/quiz/start')data={nonce:'mock-'+window.__question,mode:'practice',answered:window.__question-1,target:3,question:{id:'q'+window.__question,q:window.__question===1?'小明有三个苹果，妈妈又给了他两个苹果，现在一共有几个苹果？':'第二道题：七加八等于多少？',type:'single',a:['三个','五个','六个','十五个'],k:1}};
self.status=200;self.readyState=4;self.responseText=JSON.stringify(data);self.onreadystatechange();},10)};
</script>`;
const checks = `<script>
function assert(ok,msg){if(!ok)throw Error(msg)}
function checkFirst(){try{
var read=document.getElementById('readAloudBtn'),q=document.getElementById('qText');
assert(document.querySelector('.focused')===read,'first question does not focus reading icon');
assert(read.querySelector('svg'),'missing speaker icon');
var r=read.getBoundingClientRect(),t=q.getBoundingClientRect();
assert(r.width>=60&&r.height>=60,'reading target is too small');
assert(r.right<t.left,'reading icon must be to the left of question');
TV.select();assert(window.__calls.length===1&&window.__calls[0].indexOf('小明')===0,'OK did not read first question');
TV.move('down');assert(document.querySelector('.focused')!==read,'cannot navigate from icon to answers');
window.__question=2;window.SOTV_RESUME();setTimeout(checkNext,100);
}catch(e){document.documentElement.setAttribute('data-reading-result','FAIL: '+e.message)}}
function checkNext(){try{
assert(document.querySelector('.focused').id==='readAloudBtn','new question must refocus icon');
TV.select();assert(window.__calls.length===2&&window.__calls[1].indexOf('第二道题')===0,'OK did not read new question');
document.documentElement.setAttribute('data-reading-result','PASS');
}catch(e){document.documentElement.setAttribute('data-reading-result','FAIL: '+e.message)}}
setTimeout(checkFirst,400);
</script>`;
const source = readFileSync(resolve(root, 'app/web/index.html'), 'utf8');
const html = source.replace('<head>', `<head><base href="${pathToFileURL(resolve(root, 'app/web')).href}/">${prelude}`)
    .replace('</body>', `${checks}</body>`);
const file = resolve(output, 'reading.html');
writeFileSync(file, html);
for (const [width, height] of [[1280,720],[1920,1080]]) {
    const result = execFileSync(browser, ['--headless=new', '--no-first-run', '--allow-file-access-from-files',
        '--disable-gpu', '--hide-scrollbars', '--force-device-scale-factor=1', `--window-size=${width},${height}`,
        `--user-data-dir=${resolve(output, 'profile-'+width)}`, '--virtual-time-budget=2500', '--dump-dom',
        `--screenshot=${resolve(output, 'reading-'+width+'.png')}`, pathToFileURL(file).href],
        {encoding:'utf8', maxBuffer:10*1024*1024, stdio:['ignore','pipe','ignore']});
    const match = result.match(/data-reading-result="([^"]+)"/);
    if (match?.[1] !== 'PASS') throw Error(`${width}x${height}: ${match?.[1] || 'test did not finish'}`);
    console.log(`PASS ${width}x${height}: large left icon, initial focus, DPAD navigation, OK reading and new-question focus`);
}
