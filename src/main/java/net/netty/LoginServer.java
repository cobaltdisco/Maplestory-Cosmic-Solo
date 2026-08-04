package net.netty;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;

public class LoginServer extends AbstractServer {
    public static final int WORLD_ID = -1;
    public static final int CHANNEL_ID = -1;
    private Channel channel;
    // Kept so stop() can shut them down. As locals they outlived the server they belonged to:
    // closing the bound channel leaves both groups running, and every restart added another
    // two-per-core worth of threads that nothing would ever collect.
    private EventLoopGroup parentGroup;
    private EventLoopGroup childGroup;

    public LoginServer(int port) {
        super(port);
    }

    @Override
    public void start() {
        parentGroup = new NioEventLoopGroup();
        childGroup = new NioEventLoopGroup();
        ServerBootstrap bootstrap = new ServerBootstrap()
                .group(parentGroup, childGroup)
                .channel(NioServerSocketChannel.class)
                .childHandler(new LoginServerInitializer());

        // Local-play only: bind to loopback so the server is unreachable from other machines
        this.channel = bootstrap.bind("127.0.0.1", port).syncUninterruptibly().channel();
    }

    @Override
    public void stop() {
        if (channel == null) {
            throw new IllegalStateException("Must start LoginServer before stopping it");
        }

        channel.close().syncUninterruptibly();
        parentGroup.shutdownGracefully();
        childGroup.shutdownGracefully();
    }
}
