package net.wendal.nutzbook.module.yvr;

import static net.wendal.nutzbook.util.RedisInterceptor.jedis;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;

import org.apache.shiro.authz.annotation.RequiresPermissions;
import org.nutz.dao.Cnd;
import org.nutz.dao.pager.Pager;
import org.nutz.ioc.aop.Aop;
import org.nutz.ioc.loader.annotation.Inject;
import org.nutz.ioc.loader.annotation.IocBean;
import org.nutz.lang.Files;
import org.nutz.lang.Lang;
import org.nutz.lang.Strings;
import org.nutz.lang.random.R;
import org.nutz.lang.util.NutMap;
import org.nutz.log.Log;
import org.nutz.log.Logs;
import org.nutz.mvc.Mvcs;
import org.nutz.mvc.adaptor.WhaleAdaptor;
import org.nutz.mvc.annotation.AdaptBy;
import org.nutz.mvc.annotation.At;
import org.nutz.mvc.annotation.By;
import org.nutz.mvc.annotation.Fail;
import org.nutz.mvc.annotation.Filters;
import org.nutz.mvc.annotation.GET;
import org.nutz.mvc.annotation.Ok;
import org.nutz.mvc.annotation.POST;
import org.nutz.mvc.annotation.Param;
import org.nutz.mvc.annotation.ReqHeader;
import org.nutz.mvc.upload.TempFile;
import org.nutz.mvc.view.ForwardView;
import org.nutz.mvc.view.HttpStatusView;
import org.nutz.mvc.view.ServerRedirectView;
import org.nutz.mvc.view.UTF8JsonView;
import org.nutz.mvc.view.ViewWrapper;

import net.wendal.nutzbook.bean.CResult;
import net.wendal.nutzbook.bean.User;
import net.wendal.nutzbook.bean.UserProfile;
import net.wendal.nutzbook.bean.yvr.Topic;
import net.wendal.nutzbook.bean.yvr.TopicReply;
import net.wendal.nutzbook.bean.yvr.TopicType;
import net.wendal.nutzbook.module.BaseModule;
import net.wendal.nutzbook.mvc.CsrfActionFilter;
import net.wendal.nutzbook.service.BigContentService;
import net.wendal.nutzbook.service.PushService;
import net.wendal.nutzbook.service.RedisDao;
import net.wendal.nutzbook.service.UserService;
import net.wendal.nutzbook.service.yvr.LuceneSearchResult;
import net.wendal.nutzbook.service.yvr.TopicSearchService;
import net.wendal.nutzbook.util.Toolkit;
import redis.clients.jedis.Pipeline;
import redis.clients.jedis.Response;

@IocBean(create = "init")
@At("/yvr")
@Fail("void")
public class YvrModule extends BaseModule {

	private static final Log log = Logs.get();

	@Inject
	protected UserService userService;

	@Inject("java:$conf.getInt('topic.pageSize', 15)")
	protected int pageSize;

	@Inject("java:$conf.get('topic.image.dir')")
	protected String imageDir;
	
	@Inject
	protected RedisDao redisDao;
	
	@Inject
	protected BigContentService bigContentService;

	@At({ "/", "/index" })
	@Ok("->:/yvr/list")
	public void index() {
	}

	@Inject
	protected TopicSearchService topicSearchService;
	
	@Inject
	protected PushService pushService;

	@GET
	@At
	@Ok("beetl:yvr/_add.btl")
	public Object add(HttpSession session) {
		NutMap re = new NutMap();
		re.put("types", TopicType.values());

		String csrf = Lang.md5(R.UU16());
		session.setAttribute("_csrf", csrf);
		re.put("_csrf", csrf);
		int userId = Toolkit.uid();
		re.put("current_user", fetch_userprofile(userId));
		return re;
	}

	@POST
	@At
	@Ok("json")
	@Filters(@By(type = CsrfActionFilter.class))
	public CResult add(@Param("..")Topic topic) {
		int userId = Toolkit.uid();
		return yvrService.add(topic, userId);
	}

	@At({ "/list/?", "/list/?/?", "/list" })
	@GET
	@Ok("beetl:/yvr/index.btl")
	public Object list(String type, int page) {
		int userId = Toolkit.uid();
		Pager pager = dao.createPager(page > 0 ? page : 1, pageSize);
		String zkey = RKEY_TOPIC_UPDATE + (type == null ? "all" : type);
		return _query_topic_by_zset(zkey, pager, userId, (type == null || "all".equals(type)) ? null : TopicType.valueOf(type), null, pager.getPageNumber() == 1, "list/" + (type == null ? "all" : type));
	}
	
	@At({ "/list/u/?/?", "/list/u/?/?/?" })
	@GET
	@Ok("beetl:/yvr/index.btl")
	public Object list(String loginname, String type, int page) {
		int userId = Toolkit.uid();
		Pager pager = dao.createPager(page > 0 ? page : 1, pageSize);
		List<Topic> list = null;
		User user = dao.fetch(User.class, loginname);
		if (user == null)
			return HTTP_404;
		if ("topic".equals(type)) {
			list = yvrService.getRecentTopics(user.getId(), pager);
		} else {
			list = yvrService.getRecentReplyTopics(user.getId(), pager);
		}
		return _process_query_list(pager, list, userId, null, null, false, "list/u/" + loginname + "/" + type);
	}
	
	@At({ "/tag/?", "/tag/?/?" })
	@GET
	@Ok("beetl:/yvr/index.btl")
	public Object tag(String tagName, int page) {
		int userId = Toolkit.uid();
		if (Strings.isBlank(tagName))
			return new ServerRedirectView("/yvr/list");
		Pager pager = dao.createPager(page > 0 ? page : 1, pageSize);
		String zkey = RKEY_TOPIC_TAG + tagName.toLowerCase().trim();
		return _query_topic_by_zset(zkey, pager, userId, null, tagName, false, "tag/" + tagName);
	}
	
	protected NutMap _query_topic_by_zset(String zkey, Pager pager, 
										int userId, TopicType topicType, 
										String tagName, boolean addTop,
										String ppath) {
		List<Topic> list = redisDao.queryByZset(Topic.class, zkey, pager);
		return _process_query_list(pager, list, userId, topicType, tagName, addTop, ppath);
	}
	

	@Aop("redis")
	protected NutMap _process_query_list(Pager pager, List<Topic> list, 
										int userId, TopicType topicType, 
										String tagName, boolean addTop,
										String ppath) {
		Map<Integer, UserProfile> authors = new HashMap<Integer, UserProfile>();
		for (Topic topic : list) {
			yvrService.fillTopic(topic, authors);
		}
		
		// 查一下未回复的帖子, 最近的5条的就好了
		Set<String> no_replies_ids = jedis().zrevrangeByScore(RKEY_TOPIC_NOREPLY, Long.MAX_VALUE, 0, 0, 5);
		List<Topic> no_replies = new ArrayList<Topic>();
		for (String topicId : no_replies_ids) {
			Topic tmp = dao.fetch(Topic.class, topicId);
			if (tmp != null) {
				no_replies.add(tmp);
			}
		}
		
		NutMap re = new NutMap();
		re.put("list", list);
		re.put("pager", pager);
		re.put("type", topicType);
		re.put("tag", tagName);
		re.put("types", TopicType.values());
		re.put("ppath", ppath);
		/**
		 * var page_start = current_page - 2 > 0 ? current_page - 2 : 1; var
		 * page_end = page_start + 4 >= pages ? pages : page_start + 4;
		 */
		int page_start = pager.getPageNumber() - 2 > 0 ? pager.getPageNumber() - 2 : 1;
		int page_end = page_start + 4 >= pager.getPageCount() ? pager.getPageCount() : page_start + 4;
		re.put("page_start", page_start);
		re.put("page_end", page_end);
		re.put("current_page", pager.getPageNumber());
		re.put("pages", pager.getPageCount());
		if (userId > 0)
			re.put("current_user", fetch_userprofile(userId));
		
		// 添加未回复的列表
		if (!no_replies.isEmpty())
			re.put("no_reply_topics", no_replies);
		if (addTop)
			re.put("top_topics", yvrService.fetchTop());
		else
			re.put("top_topics", new ArrayList<>());
		
		re.put("top_tags", yvrService.fetchTopTags());
		return re;
	}

	@GET
	@At("/t/?")
	@Ok("beetl:yvr/_topic.btl")
	@Aop("redis")
	public Object topic(String id, @ReqHeader("If-None-Match")String _etag,  HttpServletResponse response) {
	    Topic topic;
	    if (id.length() == 6) {
	        topic = dao.fetch(Topic.class, Cnd.where("id", "like", id +"%"));
	        if (topic != null)
	            return new ServerRedirectView("/yvr/t/"+topic.getId());
	    } else {
	        topic = dao.fetch(Topic.class, id);
	    }
		if (topic == null) {
			return HttpStatusView.HTTP_404;
		}
		if (topic.getUserId() == 0)
			topic.setUserId(1);
        Double visited = jedis().zincrby(RKEY_TOPIC_VISIT, 1, id);
        // 检查是否匹配etag(其实就是最后回复的replay id), 如果匹配,就304呗
        String replyId = jedis().hget(RKEY_REPLY_LAST, topic.getId());
        if (replyId != null && replyId.equals(_etag)) {
            return HTTP_304;
        }
        
		topic.setAuthor(fetch_userprofile(topic.getUserId()));
		dao.fetchLinks(topic, "replies", Cnd.orderBy().asc("createTime"));
		//-------------------------------------
		// 修正userId及读取每个reply的ups
		Pipeline pipe = jedis().pipelined();
		List<Response<Set<String>>> upsList = new ArrayList<>();
		for (TopicReply reply : topic.getReplies()) {
			if (reply.getUserId() == 0)
				reply.setUserId(1);
			Response<Set<String>> resp = pipe.zrange(RKEY_REPLY_LIKE + reply.getId(), 0, Long.MAX_VALUE);
			upsList.add(resp);
		}
		pipe.sync();
		dao.fetchLinks(topic.getReplies(), null);
		Iterator<Response<Set<String>>> it = upsList.iterator();
		for (TopicReply reply : topic.getReplies()) {
			reply.setUps(it.next().get());
		}
		bigContentService.fill(topic);
		//------------------------------------
		NutMap re = new NutMap();
		re.put("topic", topic);
		int userId = Toolkit.uid();
		if (userId > 0) {
			String csrf = Lang.md5(R.UU16());
			Mvcs.getHttpSession().setAttribute("_csrf", csrf);
			re.put("_csrf", csrf);
			re.put("current_user", fetch_userprofile(userId));
		}
        topic.setVisitCount((visited == null) ? 0 : visited.intValue());
		re.put("recent_topics", yvrService.getRecentTopics(topic.getUserId(), dao.createPager(1, 5)));
		//re.put("top_tags", yvrService.fetchTopTags());
		return re;
	}

	@AdaptBy(type = WhaleAdaptor.class)
	@POST
	@At
	@Ok("json")
	@Filters(@By(type = CsrfActionFilter.class))
	public Object upload(@Param("file") TempFile tmp) throws IOException {
		int userId = Toolkit.uid();
		return yvrService.upload(tmp, userId);
	}

	@Ok("raw:jpg")
	@At("/upload/?/?")
	@Fail("http:404")
	public Object image(String p, String p2) throws IOException {
		if ((p + p2).contains("."))
			return HttpStatusView.HTTP_404;
		File f = new File(imageDir, p + "/" + p2);
		return f;
	}

	@Filters(@By(type = CsrfActionFilter.class))
	@At("/t/?/reply")
	@Ok("json")
	public Object addReply(String topicId, @Param("..") TopicReply reply) {
		int userId = Toolkit.uid();
		return yvrService.addReply(topicId, reply, userId);
	}

	@At("/t/?/reply/?/up")
	@Ok("json")
	public Object replyUp(String topicId, String replyId) {
		int userId = Toolkit.uid();
		return yvrService.replyUp(replyId, userId);
	}

	@GET
	@At
	@Ok("beetl:/yvr/index.btl")
	@Aop("redis")
	public Object search(@Param("q") String keys, @Param("format")String format) throws Exception {
	    if (Strings.isBlank(keys))
			return new ForwardView("/yvr/list");
		List<LuceneSearchResult> results = topicSearchService.search(keys, true, "json".equals(format) ? 5 : 30);
        List<Topic> list = new ArrayList<Topic>();
		for (LuceneSearchResult result : results) {
			Topic topic = dao.fetch(Topic.class, result.getId());
			if (topic == null)
				continue;
			topic.setTitle(result.getResult());
			list.add(topic);
		}
        if ("json".equals(format)) {
            return new ViewWrapper(new UTF8JsonView(), new NutMap().setv("suggestions", list));
        }
		Pager pager = dao.createPager(1, 30);
		pager.setRecordCount(list.size());
		int userId = Toolkit.uid();
		return _process_query_list(pager, list, userId, TopicType.ask, null, false, "/list/all");
	}

	@RequiresPermissions("topic:index:rebuild")
	@At("/search/rebuild")
	public void rebuild() throws IOException {
		topicSearchService.rebuild();
	}
	
	@POST
	@At("/t/?/push")
	public void push(String topicId) {
		int userId = Toolkit.uid();
		if (userId < 1)
			return;
		Map<String, String> extras = new HashMap<String, String>();
		extras.put("topic_id", topicId);
		extras.put("action", "open_topic");
		pushService.message(userId, "应用户要求推送到客户端打开帖子", extras);
	}
	
    @Ok("json")
    @At("/t/?/check")
    public Object check(String topicId, @Param("replies") int replies) {
        return yvrService.check(topicId, replies);
    }

	public void init() {
		log.debug("Image Dir = " + imageDir);
		Files.createDirIfNoExists(new File(imageDir));
	}
}
