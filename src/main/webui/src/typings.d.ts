declare module '*.png';
declare module '*.jpg';
declare module '*.jpeg';
declare module '*.gif';
declare module '*.svg';
declare module '*.css';
declare module '*.wav';
declare module '*.mp3';
declare module '*.m4a';
declare module '*.rdf';
declare module '*.ttl';
declare module '*.pdf';

interface ChatScopesSessionBuilder {
    messageHandler(handler: (message: string) => void): ChatScopesSessionBuilder;
    streamHandler(handler: (token: string) => void): ChatScopesSessionBuilder;
    errorHandler(handler: (error: string) => void): ChatScopesSessionBuilder;
    thinkingHandler(handler: (message: string) => void): ChatScopesSessionBuilder;
    connect(route?: string): Promise<ChatScopesSession>;
}

interface ChatScopesSession {
    chat(userMessage: string): Promise<void>;
    sendData(data: object): Promise<void>;
}

declare class ChatScopesClient {
    websocket: WebSocket;
    open(endpoint: string): Promise<void>;
    builder(): ChatScopesSessionBuilder;
}
