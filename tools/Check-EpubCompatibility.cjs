const http=require('http');
const fs=require('fs');
const path=require('path');
const deps=process.env.EPUB_QA_NODE_MODULES||path.resolve(__dirname,'../var/epub-renderer/node_modules');
const {chromium}=require(process.env.EPUB_QA_PLAYWRIGHT_MODULE||path.join(deps,'@playwright/test'));
const root=process.env.EPUB_SPIKE_DIR||path.resolve(__dirname,'../var/epub-spike');
const html=`<!doctype html><meta charset="utf-8"><title>EPUB compatibility QA</title>
<script src="/jszip.js"></script><script src="/epub.js"></script><div id="viewer" style="width:900px;height:650px"></div>
<script>window.openBook=async function(name){
 const data=await (await fetch('/'+name)).arrayBuffer();const book=ePub(data);await book.ready;
 const rendition=book.renderTo('viewer',{width:900,height:650,flow:'scrolled-doc',allowScriptedContent:false});
 await rendition.display();window.book=book;window.rendition=rendition;
 return {toc:book.navigation.toc,spine:book.spine.items.length};
};</script>`;
const server=http.createServer((req,res)=>{
 const url=new URL(req.url,'http://127.0.0.1');let file,type;
 if(url.pathname==='/'){res.setHeader('Content-Type','text/html; charset=utf-8');res.end(html);return;}
 if(url.pathname==='/epub.js'){file=deps+'/epubjs/dist/epub.min.js';type='application/javascript';}
 else if(url.pathname==='/jszip.js'){file=deps+'/jszip/dist/jszip.min.js';type='application/javascript';}
 else if(/^\/epub[23]-reader\.epub$/.test(url.pathname)){file=root+url.pathname;type='application/epub+zip';}
 else {res.statusCode=404;res.end();return;}
 res.setHeader('Content-Type',type);fs.createReadStream(file).pipe(res);
});
(async()=>{
 await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));
 let browser;
 try{
  browser=await chromium.launch({channel:process.env.EPUB_QA_BROWSER_CHANNEL||(process.platform==='win32'?'msedge':undefined),headless:true});const reports=[];
  for(const version of [2,3]){
   const page=await browser.newPage({viewport:{width:1000,height:760}});const errors=[];const external=[];
   page.on('pageerror',e=>errors.push(e.message));
   await page.route('**/*',route=>{const u=route.request().url();if(u.startsWith('http')&&!u.startsWith('http://127.0.0.1:')){external.push(u);return route.abort();}return route.continue();});
   await page.goto('http://127.0.0.1:'+server.address().port);
   const initial=await page.evaluate(name=>window.openBook(name),'epub'+version+'-reader.epub');
   if(initial.spine!==2||initial.toc.length!==2||initial.toc[0].subitems.length!==1)throw Error('TOC/spine hierarchy mismatch');
   const assets=await page.evaluate(async()=>{const c=window.rendition.getContents()[0];await c.document.fonts.load('18px EpubTestCC0','A');const image=c.document.querySelector('img');if(image&&!image.complete)await new Promise(r=>image.onload=r);return {text:c.document.body.textContent,images:image?.naturalWidth,fonts:[...c.document.fonts].map(f=>({family:f.family,status:f.status})),attack:window.EPUB_ATTACK===true||c.window.EPUB_ATTACK===true,script:c.document.querySelectorAll('script,iframe,[onclick]').length};});
   if(!assets.text.includes('Xin chào 😀')||assets.images!==1||!assets.fonts.some(f=>f.family.includes('EpubTestCC0')&&f.status==='loaded')||assets.attack||assets.script)throw Error('Asset/sanitizer check failed: '+JSON.stringify(assets));
   const href=initial.toc[0].subitems[0].href;
   await page.evaluate(href=>window.rendition.display(href),href);
   const fragment=await page.evaluate(async()=>{await new Promise(r=>requestAnimationFrame(()=>requestAnimationFrame(r)));const contents=window.rendition.getContents();for(const c of contents){const el=c.document.getElementById('phần-hai');if(el){const rect=el.getBoundingClientRect();return {text:el.textContent,top:rect.top,screenTop:rect.top+c.window.frameElement.getBoundingClientRect().top,href:window.book.spine.get(c.sectionIndex).href};}}return null;});
   if(!fragment||fragment.text!=='Phần hai'||fragment.screenTop<0||fragment.screenTop>650||!href.split('#')[0].endsWith(fragment.href.split('/').at(-1)))throw Error('Fragment target mismatch: '+JSON.stringify(fragment));
   await page.screenshot({path:root+'/epub'+version+'-render.png',fullPage:true});
   if(external.length||errors.length)throw Error('Unexpected requests/errors: '+JSON.stringify({external,errors}));
   reports.push({version,renderer:'epubjs@0.3.93',browser:await browser.version(),initial,assets,fragment,external,errors});await page.close();
  }
  fs.writeFileSync(root+'/browser-qa.json',JSON.stringify(reports,null,2));console.log(JSON.stringify(reports));
 }finally{if(browser)await browser.close();await new Promise(resolve=>server.close(resolve));}
})().catch(e=>{console.error(e);process.exitCode=1;});
