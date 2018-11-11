package cc.lovezhy.raft.rpc.protocal;

public class RpcRequest {
    private String requestId;
    private RpcRequestType requestType;

    private String clazz;
    private String method;
    private Object[] args;

    public String getRequestId() {
        return requestId;
    }

    public void setRequestId(String requestId) {
        this.requestId = requestId;
    }

    public RpcRequestType getRequestType() {
        return requestType;
    }

    public void setRequestType(RpcRequestType requestType) {
        this.requestType = requestType;
    }

    public String getClazz() {
        return clazz;
    }

    public void setClazz(String clazz) {
        this.clazz = clazz;
    }

    public String getMethod() {
        return method;
    }

    public void setMethod(String method) {
        this.method = method;
    }

    public Object[] getArgs() {
        return args;
    }

    public void setArgs(Object[] args) {
        this.args = args;
    }
}