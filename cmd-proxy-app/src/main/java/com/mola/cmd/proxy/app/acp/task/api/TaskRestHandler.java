package com.mola.cmd.proxy.app.acp.task.api;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.mola.cmd.proxy.app.acp.task.model.TaskException;
import com.mola.cmd.proxy.app.acp.task.service.TaskService;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.*;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/** Independently registerable REST boundary, also reusable by task MCP HTTP listener. */
public final class TaskRestHandler implements HttpHandler {
    public static final String PREFIX="/api/starweave/v1/tasks";
    private final TaskService service;
    public TaskRestHandler(TaskService service){this.service=service;}
    public void handle(HttpExchange exchange)throws IOException {
        try {
            String path=exchange.getRequestURI().getPath();if(!path.equals(PREFIX)&&!path.startsWith(PREFIX+"/"))throw new TaskException("NOT_FOUND","Route not found",404);
            String suffix=path.substring(PREFIX.length()),method=exchange.getRequestMethod();JSONObject query=query(exchange.getRequestURI().getRawQuery());
            if(suffix.startsWith("/attachments/")&&suffix.endsWith("/download")&&method.equals("GET")){
                String id=suffix.substring(13,suffix.length()-9);JSONObject meta=service.getAttachments().metadata(id);
                try(InputStream input=service.getAttachments().open(id)){exchange.getResponseHeaders().set("Content-Type","application/octet-stream");exchange.getResponseHeaders().set("X-Content-Type-Options","nosniff");exchange.getResponseHeaders().set("Content-Disposition","attachment; filename*=UTF-8''"+URLEncoder.encode(meta.getString("fileName"),"UTF-8").replace("+","%20"));exchange.sendResponseHeaders(200,meta.getLongValue("size"));try(OutputStream output=exchange.getResponseBody()){byte[] b=new byte[8192];int n;while((n=input.read(b))!=-1)output.write(b,0,n);}}return;
            }
            JSONObject data;
            if(suffix.equals("/attachments")&&method.equals("POST")){try(InputStream body=exchange.getRequestBody()){data=service.getAttachments().upload(query.getString("fileName"),exchange.getRequestHeaders().getFirst("Content-Type"),body);}}
            else if(suffix.equals("/publish")&&method.equals("POST")){JSONObject request=body(exchange);if(request.getJSONObject("target")!=null)throw new TaskException("INVALID_ARGUMENT","Published tasks cannot specify a target",400);data=service.create(request);}
            else if(suffix.equals("/stats")&&method.equals("GET"))data=service.stats(query);
            else if(suffix.isEmpty()||suffix.equals("/")){if(method.equals("GET"))data=service.list(query);else if(method.equals("POST"))data=service.create(body(exchange));else throw method();}
            else {String[] parts=suffix.substring(1).split("/");String id=parts[0];
                if(parts.length==1){if(method.equals("GET"))data=service.get(id,query);else if(method.equals("PATCH"))data=service.edit(id,body(exchange));else if(method.equals("DELETE"))data=service.delete(id,body(exchange));else throw method();}
                else if(parts.length==2&&parts[1].equals("assign")&&method.equals("POST")){
                    JSONObject request=body(exchange);com.mola.cmd.proxy.app.acp.task.dispatch.TaskTargetResolver resolver=TaskApiBridge.getResolver();
                    data=service.assign(id,request,resolver.candidates(request.getJSONObject("target")),resolver.assignmentMode(request.getJSONObject("target")));
                }
                else if(parts.length==2&&parts[1].equals("derived")&&method.equals("GET"))data=service.derivedTasks(id,query);
                else if(parts.length==2&&parts[1].equals("status")&&method.equals("POST"))data=service.updateStatus(id,body(exchange));
                else if(parts.length==2&&parts[1].equals("comments")){if(method.equals("GET"))data=service.comments(id,query);else if(method.equals("POST"))data=service.addComment(id,body(exchange));else throw method();}
                else if(parts.length==2&&parts[1].equals("history")&&method.equals("GET"))data=service.history(id,query);
                else if(parts.length==3&&parts[1].equals("history")&&method.equals("GET")){query.put("revision",parts[2]);data=service.get(id,query);}
                else if(parts.length==3&&parts[1].equals("delivery")&&parts[2].equals("retry")&&method.equals("POST"))data=service.retryDelivery(id);
                else throw new TaskException("NOT_FOUND","Route not found",404);
            }
            send(exchange,200,envelope(true,"OK","OK",data));
        }catch(TaskException e){send(exchange,e.getHttpStatus(),envelope(false,e.getCode(),e.getMessage(),e.getData()));}
        catch(com.alibaba.fastjson.JSONException|IllegalArgumentException e){send(exchange,400,envelope(false,"INVALID_ARGUMENT","Invalid request",new JSONObject()));}
        catch(RuntimeException e){send(exchange,503,envelope(false,"STORAGE_UNAVAILABLE","Task service unavailable",new JSONObject()));}
        finally{exchange.close();}
    }
    public static JSONObject envelope(boolean accepted,String code,String message,JSONObject data){JSONObject value=new JSONObject(true);value.put("accepted",accepted);value.put("code",code);value.put("message",message);value.put("data",data);return value;}
    private static TaskException method(){return new TaskException("METHOD_NOT_ALLOWED","Method not allowed",405);}
    private static JSONObject body(HttpExchange e)throws IOException{try(InputStream in=e.getRequestBody();ByteArrayOutputStream bytes=new ByteArrayOutputStream()){byte[] b=new byte[8192];int n;while((n=in.read(b))!=-1){if(bytes.size()+n>2*1024*1024)throw new TaskException("RESOURCE_LIMIT","JSON body too large",413);bytes.write(b,0,n);}JSONObject value=JSON.parseObject(new String(bytes.toByteArray(),StandardCharsets.UTF_8));if(value==null)throw new TaskException("INVALID_ARGUMENT","Object body required",400);return value;}}
    private static JSONObject query(String raw)throws UnsupportedEncodingException{JSONObject result=new JSONObject();if(raw!=null)for(String pair:raw.split("&")){String[] pieces=pair.split("=",2);String key=URLDecoder.decode(pieces[0],"UTF-8");if(result.containsKey(key))throw new TaskException("INVALID_ARGUMENT","Duplicate query parameter",400);result.put(key,pieces.length==1?"":URLDecoder.decode(pieces[1],"UTF-8"));}return result;}
    private static void send(HttpExchange e,int status,JSONObject value)throws IOException{byte[] data=com.alibaba.fastjson.JSON.toJSONString(value, com.alibaba.fastjson.serializer.SerializerFeature.WriteMapNullValue).getBytes(StandardCharsets.UTF_8);e.getResponseHeaders().set("Content-Type","application/json; charset=utf-8");e.getResponseHeaders().set("Cache-Control","no-store");e.sendResponseHeaders(status,data.length);try(OutputStream out=e.getResponseBody()){out.write(data);}}
}
