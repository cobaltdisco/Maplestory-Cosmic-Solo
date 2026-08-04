package net.netty;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;

public class ChannelServer extends AbstractServer {
    private final int world;
    private final int channel;
    private Channel nettyChannel;
    // See LoginServer: stop() has to shut these down, or a restart leaves them behind.
    private EventLoopGroup parentGroup;
    private EventLoopGroup childGroup;

    public ChannelServer(int port, int world, int channel) {
        super(port);
        this.world = world;
        this.channel = channel;
    }

    @Override
    public void start() {
        parentGroup = new NioEventLoopGroup();
        childGroup = new NioEventLoopGroup();
        ServerBootstrap bootstrap = new ServerBootstrap()
                .group(parentGroup, childGroup)
                .channel(NioServerSocketChannel.class)
                .childHandler(new ChannelServerInitializer(world, channel));

        // Local-play only: bind to loopback so the server is unreachable from other machines
        this.nettyChannel = bootstrap.bind("127.0.0.1", port).syncUninterruptibly().channel();
    }

    @Override
    public void stop() {
        if (nettyChannel == null) {
            throw new IllegalStateException("Must start ChannelServer before stopping it");
        }

        nettyChannel.close().syncUninterruptibly();
        parentGroup.shutdownGracefully();
        childGroup.shutdownGracefully();
    }
}
